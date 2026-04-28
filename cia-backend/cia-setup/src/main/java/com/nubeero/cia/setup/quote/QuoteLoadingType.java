package com.nubeero.cia.setup.quote;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "quote_loading_types")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class QuoteLoadingType extends BaseEntity {

    @Column(nullable = false, length = 200, unique = true)
    private String name;
}
