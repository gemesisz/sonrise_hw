package com.sonrise.alerting.admin;

import com.sonrise.alerting.admin.dto.CategoryResponse;
import com.sonrise.alerting.admin.dto.ChannelResponse;
import com.sonrise.alerting.domain.Channel;
import com.sonrise.alerting.repository.CategoryRepository;
import com.sonrise.alerting.repository.ChannelRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class ReferenceDataService {

    private final CategoryRepository categories;
    private final ChannelRepository channels;

    public ReferenceDataService(CategoryRepository categories, ChannelRepository channels) {
        this.categories = categories;
        this.channels = channels;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> categories() {
        return categories.findAll(Sort.by("code")).stream()
                .map(c -> new CategoryResponse(c.getCode(), c.getName(), c.getDescription()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ChannelResponse> channels() {
        return channels.findAll(Sort.by("code")).stream().map(ReferenceDataService::toResponse).toList();
    }

    /**
     * A disabled channel delivers nothing to anyone, e.g. during a Slack outage.
     */
    public ChannelResponse setChannelEnabled(String code, boolean enabled) {
        Channel channel = channels.findByCode(code)
                .orElseThrow(() -> new NotFoundException("Channel " + code + " not found"));
        channel.setEnabled(enabled);
        return toResponse(channel);
    }

    private static ChannelResponse toResponse(Channel channel) {
        return new ChannelResponse(channel.getCode(), channel.getName(), channel.isEnabled());
    }
}
