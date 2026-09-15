package com.miqa.store.admin;
import com.miqa.store.catalog.ProductSaleType;
import jakarta.validation.constraints.*;
import java.util.List;
public final class AdminDtos {
 private AdminDtos(){}
 public record CategoryInput(@NotBlank @Size(max=160) String name,@NotBlank @Size(max=160) @Pattern(regexp="^[a-z0-9]+(-[a-z0-9]+)*$") String slug,@Size(max=10000) String description,
  @Size(max=200) @Pattern(regexp="^[^<>]*$") String catalogHeadline,@Size(max=500) @Pattern(regexp="^[^<>]*$") String catalogDescription,
  @NotNull Boolean active,@NotNull @Min(0) Integer displayOrder){}
 public record CategoryView(String id,String name,String slug,String description,String catalogHeadline,String catalogDescription,boolean active,int displayOrder){}
 public record ProductInput(@NotBlank @Size(max=64) String categoryId,@NotBlank @Size(max=200) String name,
  @NotBlank @Size(max=160) @Pattern(regexp="^[a-z0-9]+(-[a-z0-9]+)*$") String slug,
  @NotNull @Size(max=500) String shortDescription,@NotNull @Size(max=20000) String description,
  @NotNull ProductSaleType saleType,@NotBlank @Size(max=40) String unitLabel,
  @Positive Integer packSize,@Size(max=40) String packLabel,@Positive Integer minQuantity,@Positive Integer quantityStep,
  @NotNull Boolean featured,@NotNull Boolean published,@NotNull @Min(0) Integer displayOrder,
  @Size(max=200) String seoTitle,@Size(max=500) String seoDescription){}
 public record ProductView(String id,String categoryId,CategoryView category,String name,String slug,String shortDescription,String description,
  ProductSaleType saleType,String unitLabel,Integer packSize,String packLabel,Integer minQuantity,Integer quantityStep,
  boolean featured,boolean published,int displayOrder,String seoTitle,String seoDescription,String image,
  List<OptionView> materials,List<OptionView> extras,List<ImageView> images){}
 public record OptionInput(@NotBlank @Size(max=160) String name,@NotNull Boolean active,@NotNull @Min(0) Integer displayOrder){}
 public record OptionView(String id,String name,boolean active,int displayOrder){}
 public record ImageInput(@NotBlank @Size(max=2048) String url,@NotNull @Size(max=300) String altText,@NotNull Boolean primaryImage,@NotNull @Min(0) Integer displayOrder){}
 public record ImageView(String id,String url,String publicUrl,String altText,boolean primaryImage,int displayOrder){}
 public record Active(@NotNull Boolean active){}
 public record Published(@NotNull Boolean published){}
 public record Featured(@NotNull Boolean featured){}
 public record Primary(@NotNull Boolean primaryImage){}
}
