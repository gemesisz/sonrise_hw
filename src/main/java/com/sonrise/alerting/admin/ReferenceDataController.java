package com.sonrise.alerting.admin;

import com.sonrise.alerting.admin.dto.CategoryResponse;
import com.sonrise.alerting.admin.dto.ChannelResponse;
import com.sonrise.alerting.admin.dto.ChannelUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class ReferenceDataController {

    private final ReferenceDataService service;

    public ReferenceDataController(ReferenceDataService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    public List<CategoryResponse> categories() {
        return service.categories();
    }

    @GetMapping("/channels")
    public List<ChannelResponse> channels() {
        return service.channels();
    }

    @PatchMapping("/channels/{code}")
    public ChannelResponse updateChannel(@PathVariable String code, @Valid @RequestBody ChannelUpdateRequest request) {
        return service.setChannelEnabled(code, request.enabled());
    }
}
