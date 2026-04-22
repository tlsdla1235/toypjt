package com.sst.flaggame.domain.user.entity;

import com.sst.flaggame.common.entity.DeletedAtConverter;
import com.sst.flaggame.domain.user.Role;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.SoftDelete;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "users")

// hibernate 이슈인듯? softdelte값을 boolean에서 timedate로 coevert를 하면 오류가 발생한다는 거 같다는 이슈가 보고됨
@SQLDelete(sql = "UPDATE users SET deleted_at = NOW() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long githubId;

    @Column(nullable = false, length = 64)
    private String login;

    @Column(length = 512)
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @Column(nullable = false)
    private boolean blocked = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder
    public User(Long githubId, String login, String avatarUrl, Role role) {
        this.githubId = githubId;
        this.login = login;
        this.avatarUrl = avatarUrl;
        if (role != null) {
            this.role = role;
        }
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public void updateProfile(String login, String avatarUrl) {
        this.login = login;
        this.avatarUrl = avatarUrl;
    }

    public void block() {
        this.blocked = true;
    }

    public void unblock() {
        this.blocked = false;
    }

    public void promoteToAdmin() {
        this.role = Role.ADMIN;
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}