package com.miqa.store.admin;
import com.miqa.store.admin.AdminDtos.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/admin")
public class AdminCatalogController {
 private final AdminCatalogService service;
 public AdminCatalogController(AdminCatalogService service){this.service=service;}
 @GetMapping("/categories") public List<CategoryView> categories(){return service.categories();}
 @GetMapping("/categories/{id}") public CategoryView category(@PathVariable String id){return service.category(id);}
 @PostMapping("/categories") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public CategoryView createCategory(@Valid @RequestBody CategoryInput r){return service.saveCategory(null,r);}
 @PutMapping("/categories/{id}") public CategoryView category(@PathVariable String id,@Valid @RequestBody CategoryInput r){return service.saveCategory(id,r);}
 @PatchMapping("/categories/{id}/active") public CategoryView active(@PathVariable String id,@Valid @RequestBody Active r){return service.activeCategory(id,r.active());}
 @DeleteMapping("/categories/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) public void deleteCategory(@PathVariable String id){service.deleteCategory(id);}
 @GetMapping("/products") public List<ProductView> products(@RequestParam(required=false) @Size(max=64) String category,@RequestParam(required=false) @Size(max=120) String search,@RequestParam(required=false) Boolean published,@RequestParam(required=false) Boolean featured){return service.products(category,search,published,featured);}
 @GetMapping("/products/{id}") public ProductView product(@PathVariable String id){return service.product(id);}
 @PostMapping("/products") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public ProductView createProduct(@Valid @RequestBody ProductInput r){return service.saveProduct(null,r);}
 @PutMapping("/products/{id}") public ProductView product(@PathVariable String id,@Valid @RequestBody ProductInput r){return service.saveProduct(id,r);}
 @PatchMapping("/products/{id}/published") public ProductView published(@PathVariable String id,@Valid @RequestBody Published r){return service.published(id,r.published());}
 @PatchMapping("/products/{id}/featured") public ProductView featured(@PathVariable String id,@Valid @RequestBody Featured r){return service.featured(id,r.featured());}
 @GetMapping("/products/{pid}/materials") public List<OptionView> materials(@PathVariable String pid){return service.materials(pid);}
 @PostMapping("/products/{pid}/materials") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public OptionView creatematerials(@PathVariable String pid,@Valid @RequestBody OptionInput r){return service.savematerials(pid,null,r);}
 @PutMapping("/products/{pid}/materials/{id}") public OptionView materials(@PathVariable String pid,@PathVariable String id,@Valid @RequestBody OptionInput r){return service.savematerials(pid,id,r);}
 @DeleteMapping("/products/{pid}/materials/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) public void deleteMaterial(@PathVariable String pid,@PathVariable String id){service.deleteMaterial(pid,id);}
 @PatchMapping("/products/{pid}/materials/{id}/active") public OptionView activematerials(@PathVariable String pid,@PathVariable String id,@Valid @RequestBody Active r){return service.activematerials(pid,id,r.active());}
 @GetMapping("/products/{pid}/extras") public List<OptionView> extras(@PathVariable String pid){return service.extras(pid);}
 @PostMapping("/products/{pid}/extras") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public OptionView createextras(@PathVariable String pid,@Valid @RequestBody OptionInput r){return service.saveextras(pid,null,r);}
 @PutMapping("/products/{pid}/extras/{id}") public OptionView extras(@PathVariable String pid,@PathVariable String id,@Valid @RequestBody OptionInput r){return service.saveextras(pid,id,r);}
 @PatchMapping("/products/{pid}/extras/{id}/active") public OptionView activeextras(@PathVariable String pid,@PathVariable String id,@Valid @RequestBody Active r){return service.activeextras(pid,id,r.active());}
 @PostMapping(value="/products/{pid}/images/upload", consumes="multipart/form-data")
 @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
 public ImageView upload(@PathVariable String pid, @RequestParam org.springframework.web.multipart.MultipartFile file,
     @RequestParam(required=false) String altText, @RequestParam(required=false) Integer displayOrder,
     @RequestParam(defaultValue="false") boolean primaryImage) {
  return service.upload(pid,file,altText,displayOrder,primaryImage);
 }
 @PostMapping("/products/{pid}/images/{id}/remove")
 @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
 public void removeImage(@PathVariable String pid,@PathVariable String id){service.removeImage(pid,id);}
 @GetMapping("/products/{pid}/images") public List<ImageView> images(@PathVariable String pid){return service.images(pid);}
 @PostMapping("/products/{pid}/images") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public ImageView createImage(@PathVariable String pid,@Valid @RequestBody ImageInput r){return service.saveImage(pid,null,r);}
 @PutMapping("/products/{pid}/images/{id}") public ImageView image(@PathVariable String pid,@PathVariable String id,@Valid @RequestBody ImageInput r){return service.saveImage(pid,id,r);}
 @PatchMapping("/products/{pid}/images/{id}/primary") public ImageView primary(@PathVariable String pid,@PathVariable String id,@Valid @RequestBody Primary r){return service.primary(pid,id,r.primaryImage());}
}
