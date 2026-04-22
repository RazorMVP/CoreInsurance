package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "product_sections")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductSection extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal rate;

    @Column(name = "order_no", nullable = false)
    private int orderNo;
}
