package com.nubeero.cia.setup.quote;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "quote_discount_types")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class QuoteDiscountType extends BaseEntity {

    @Column(nullable = false, length = 200, unique = true)
    private String name;
}
