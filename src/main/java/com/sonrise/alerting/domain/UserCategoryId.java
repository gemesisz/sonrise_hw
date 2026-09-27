package com.sonrise.alerting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class UserCategoryId implements Serializable {

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "category_id")
    private Long categoryId;

    protected UserCategoryId() {
    }

    public UserCategoryId(Long userId, Long categoryId) {
        this.userId = userId;
        this.categoryId = categoryId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserCategoryId other)) {
            return false;
        }
        return Objects.equals(userId, other.userId) && Objects.equals(categoryId, other.categoryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, categoryId);
    }
}
