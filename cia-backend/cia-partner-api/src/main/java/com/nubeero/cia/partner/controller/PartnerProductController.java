package com.nubeero.cia.partner.controller;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.partner.controller.dto.PartnerClassOfBusinessResponse;
import com.nubeero.cia.partner.controller.dto.PartnerProductResponse;
import com.nubeero.cia.setup.product.ClassOfBusinessService;
import com.nubeero.cia.setup.product.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/partner/v1/products")
@Tag(name = "Products", description = "Insurance products available to Insurtech partners")
@SecurityRequirement(name = "bearer-key")
@RequiredArgsConstructor
public class PartnerProductController {

    private final ProductService productService;
    private final ClassOfBusinessService classOfBusinessService;

    @GetMapping
    @Operation(summary = "List active products",
               description = "Returns all active insurance products for the authenticated tenant")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Products retrieved",
            content = @Content(schema = @Schema(implementation = PartnerProductResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — invalid or expired token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — insufficient scope", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    })
    public ResponseEntity<ApiResponse<Page<PartnerProductResponse>>> list(
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        Page<PartnerProductResponse> page = productService.list(pageable)
                .map(PartnerProductResponse::from);
        return ResponseEntity.ok(ApiResponse.success(page));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get product details",
               description = "Returns full details for a single product including rate and minimum premium")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Product found",
            content = @Content(schema = @Schema(implementation = PartnerProductResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — invalid or expired token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — insufficient scope", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Product not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    })
    public ResponseEntity<ApiResponse<PartnerProductResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(
                PartnerProductResponse.from(productService.get(id))));
    }

    @GetMapping("/{id}/classes")
    @Operation(summary = "List classes of business under a product",
               description = "Returns the class(es) of business associated with the product — used to populate quote requests")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Classes retrieved",
            content = @Content(schema = @Schema(implementation = PartnerClassOfBusinessResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — invalid or expired token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — insufficient scope", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Product not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    })
    public ResponseEntity<ApiResponse<List<PartnerClassOfBusinessResponse>>> listClasses(@PathVariable UUID id) {
        var product = productService.get(id);
        var cob = classOfBusinessService.get(product.getClassOfBusinessId());
        return ResponseEntity.ok(ApiResponse.success(
                List.of(PartnerClassOfBusinessResponse.from(cob))));
    }
}
