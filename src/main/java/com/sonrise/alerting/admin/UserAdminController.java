package com.sonrise.alerting.admin;

import com.sonrise.alerting.admin.dto.ChannelLinkRequest;
import com.sonrise.alerting.admin.dto.ChannelLinkResponse;
import com.sonrise.alerting.admin.dto.ChannelUpdateRequest;
import com.sonrise.alerting.admin.dto.SubscriptionRequest;
import com.sonrise.alerting.admin.dto.SubscriptionResponse;
import com.sonrise.alerting.admin.dto.UserRequest;
import com.sonrise.alerting.admin.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<UserResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest request) {
        UserResponse created = service.create(request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public UserResponse rename(@PathVariable Long id, @Valid @RequestBody UserRequest request) {
        return service.rename(id, request);
    }

    /** Also deletes the user's subscriptions, channel links and notification history. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /** Creates or updates the subscription (idempotent). */
    @PutMapping("/{id}/subscriptions/{category}")
    public SubscriptionResponse subscribe(@PathVariable Long id, @PathVariable String category,
                                          @Valid @RequestBody SubscriptionRequest request) {
        return service.subscribe(id, category, request);
    }

    @DeleteMapping("/{id}/subscriptions/{category}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@PathVariable Long id, @PathVariable String category) {
        service.unsubscribe(id, category);
    }

    /** Creates or updates the channel link (idempotent); the address is validated by the channel. */
    @PutMapping("/{id}/channels/{channel}")
    public ChannelLinkResponse linkChannel(@PathVariable Long id, @PathVariable String channel,
                                           @Valid @RequestBody ChannelLinkRequest request) {
        return service.linkChannel(id, channel, request);
    }

    /** Enables or disables a channel link without re-sending the (possibly secret) address. */
    @PatchMapping("/{id}/channels/{channel}")
    public ChannelLinkResponse setChannelEnabled(@PathVariable Long id, @PathVariable String channel,
                                                 @Valid @RequestBody ChannelUpdateRequest request) {
        return service.setChannelEnabled(id, channel, request.enabled());
    }

    @DeleteMapping("/{id}/channels/{channel}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkChannel(@PathVariable Long id, @PathVariable String channel) {
        service.unlinkChannel(id, channel);
    }
}
