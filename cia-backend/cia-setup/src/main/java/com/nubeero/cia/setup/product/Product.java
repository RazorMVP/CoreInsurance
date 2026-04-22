package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "products")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_of_business_id", nullable = false)
    private ClassOfBusiness classOfBusiness;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductType type;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal rate;

    @Column(name = "min_premium", nullable = false, precision = 18, scale = 2)
    private BigDecimal minPremium;

    @Column(nullable = false)
    private boolean active;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderNo ASC")
    @Builder.Default
    private List<ProductSection> sections = new ArrayList<>();
}
