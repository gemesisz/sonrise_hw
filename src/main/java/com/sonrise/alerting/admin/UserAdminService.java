package com.sonrise.alerting.admin;

import com.sonrise.alerting.admin.dto.ChannelLinkRequest;
import com.sonrise.alerting.admin.dto.ChannelLinkResponse;
import com.sonrise.alerting.admin.dto.SubscriptionRequest;
import com.sonrise.alerting.admin.dto.SubscriptionResponse;
import com.sonrise.alerting.admin.dto.UserRequest;
import com.sonrise.alerting.admin.dto.UserResponse;
import com.sonrise.alerting.channel.NotificationChannel;
import com.sonrise.alerting.channel.NotificationChannelRegistry;
import com.sonrise.alerting.domain.AppUser;
import com.sonrise.alerting.domain.Category;
import com.sonrise.alerting.domain.Channel;
import com.sonrise.alerting.domain.UserCategory;
import com.sonrise.alerting.domain.UserCategoryId;
import com.sonrise.alerting.domain.UserChannel;
import com.sonrise.alerting.domain.UserChannelId;
import com.sonrise.alerting.repository.AppUserRepository;
import com.sonrise.alerting.repository.CategoryRepository;
import com.sonrise.alerting.repository.ChannelRepository;
import com.sonrise.alerting.repository.NotificationRepository;
import com.sonrise.alerting.repository.UserCategoryRepository;
import com.sonrise.alerting.repository.UserChannelRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Users, their category subscriptions and their channel links.
 */
@Service
@Transactional
public class UserAdminService {

    private final AppUserRepository users;
    private final CategoryRepository categories;
    private final ChannelRepository channels;
    private final UserCategoryRepository subscriptions;
    private final UserChannelRepository userChannels;
    private final NotificationRepository notifications;
    private final NotificationChannelRegistry channelRegistry;

    public UserAdminService(AppUserRepository users, CategoryRepository categories, ChannelRepository channels,
                            UserCategoryRepository subscriptions, UserChannelRepository userChannels,
                            NotificationRepository notifications, NotificationChannelRegistry channelRegistry) {
        this.users = users;
        this.categories = categories;
        this.channels = channels;
        this.subscriptions = subscriptions;
        this.userChannels = userChannels;
        this.notifications = notifications;
        this.channelRegistry = channelRegistry;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return toResponses(users.findAll(Sort.by("id")));
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long id) {
        return toResponses(List.of(user(id))).getFirst();
    }

    public UserResponse create(UserRequest request) {
        return get(users.save(new AppUser(request.name().trim())).getId());
    }

    public UserResponse rename(Long id, UserRequest request) {
        user(id).setName(request.name().trim());
        return get(id);
    }

    /**
     * Deletes the user with their subscriptions, channel links (database cascade) and their
     * notification history (explicitly here; the database blocks it otherwise, see D43).
     */
    public void delete(Long id) {
        AppUser user = user(id);
        notifications.deleteByUserId(id);
        users.delete(user);
    }

    public SubscriptionResponse subscribe(Long userId, String categoryCode, SubscriptionRequest request) {
        AppUser user = user(userId);
        Category category = categories.findByCode(categoryCode)
                .orElseThrow(() -> new NotFoundException("Category " + categoryCode + " not found"));
        UserCategory subscription = subscriptions.findById(new UserCategoryId(userId, category.getId()))
                .orElseGet(() -> subscriptions.save(new UserCategory(user, category, request.minSeverity())));
        subscription.setMinSeverity(request.minSeverity());
        return new SubscriptionResponse(category.getCode(), subscription.getMinSeverity());
    }

    public void unsubscribe(Long userId, String categoryCode) {
        user(userId);
        Category category = categories.findByCode(categoryCode)
                .orElseThrow(() -> new NotFoundException("Category " + categoryCode + " not found"));
        UserCategoryId id = new UserCategoryId(userId, category.getId());
        if (!subscriptions.existsById(id)) {
            throw new NotFoundException("User " + userId + " is not subscribed to " + categoryCode);
        }
        subscriptions.deleteById(id);
    }

    public ChannelLinkResponse linkChannel(Long userId, String channelCode, ChannelLinkRequest request) {
        AppUser user = user(userId);
        Channel channel = channel(channelCode);
        NotificationChannel implementation = channelRegistry.get(channel.getCode());
        String address = request.address().trim();
        implementation.validateAddress(address); // InvalidAddressException -> 400 with the reason

        UserChannel link = userChannels.findById(new UserChannelId(userId, channel.getId()))
                .orElseGet(() -> userChannels.save(new UserChannel(user, channel, address)));
        link.setAddress(address);
        if (request.enabled() != null) {
            link.setEnabled(request.enabled());
        }
        return new ChannelLinkResponse(channel.getCode(), implementation.displayAddress(address), link.isEnabled());
    }

    public void unlinkChannel(Long userId, String channelCode) {
        user(userId);
        UserChannelId id = new UserChannelId(userId, channel(channelCode).getId());
        if (!userChannels.existsById(id)) {
            throw new NotFoundException("User " + userId + " has no " + channelCode + " channel");
        }
        userChannels.deleteById(id);
    }

    private AppUser user(Long id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("User " + id + " not found"));
    }

    private Channel channel(String code) {
        return channels.findByCode(code).orElseThrow(() -> new NotFoundException("Channel " + code + " not found"));
    }

    /** Two queries for any number of users, instead of two per user. */
    private List<UserResponse> toResponses(List<AppUser> list) {
        List<Long> ids = list.stream().map(AppUser::getId).toList();
        Map<Long, List<SubscriptionResponse>> subscriptionsByUser = subscriptions.findWithCategoryByUserIdIn(ids)
                .stream()
                .sorted(Comparator.comparing(s -> s.getCategory().getCode()))
                .collect(Collectors.groupingBy(s -> s.getId().getUserId(), Collectors.mapping(
                        s -> new SubscriptionResponse(s.getCategory().getCode(), s.getMinSeverity()),
                        Collectors.toList())));
        Map<Long, List<ChannelLinkResponse>> channelsByUser = userChannels.findWithChannelByUserIdIn(ids)
                .stream()
                .sorted(Comparator.comparing(link -> link.getChannel().getCode()))
                .collect(Collectors.groupingBy(link -> link.getId().getUserId(), Collectors.mapping(
                        link -> new ChannelLinkResponse(link.getChannel().getCode(),
                                channelRegistry.get(link.getChannel().getCode()).displayAddress(link.getAddress()),
                                link.isEnabled()),
                        Collectors.toList())));
        return list.stream()
                .map(user -> new UserResponse(user.getId(), user.getName(), user.getCreatedAt(),
                        subscriptionsByUser.getOrDefault(user.getId(), List.of()),
                        channelsByUser.getOrDefault(user.getId(), List.of())))
                .toList();
    }
}
