package com.sonrise.alerting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/**
 * A user's subscription to a category: they are notified of events in it
 * whose severity is at least {@link #minSeverity}.
 */
@Entity
@Table(name = "user_category")
public class UserCategory {

    @EmbeddedId
    private UserCategoryId id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @MapsId("categoryId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id")
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "min_severity", nullable = false, length = 20)
    private Severity minSeverity = Severity.LOW;

    protected UserCategory() {
    }

    public UserCategory(AppUser user, Category category, Severity minSeverity) {
        this.id = new UserCategoryId(user.getId(), category.getId());
        this.user = user;
        this.category = category;
        this.minSeverity = minSeverity;
    }

    public UserCategoryId getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public Category getCategory() {
        return category;
    }

    public Severity getMinSeverity() {
        return minSeverity;
    }

    public void setMinSeverity(Severity minSeverity) {
        this.minSeverity = minSeverity;
    }
}
