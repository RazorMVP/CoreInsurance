package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "policy_specifications")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicySpecification extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, unique = true)
    private Product product;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;
}
