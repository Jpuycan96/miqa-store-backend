package com.miqa.store.admin;
import com.miqa.store.catalog.*;
import com.miqa.store.config.MediaProperties;
import com.miqa.store.admin.AdminDtos.*;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import java.util.*;
@Service @Transactional(readOnly=true)
public class AdminCatalogService {
 private final CategoryRepository categories;private final ProductRepository products;private final EntityManager em;private final MediaProperties media;private final ProductImageStorage storage;
 public AdminCatalogService(CategoryRepository categories,ProductRepository products,EntityManager em,MediaProperties media,ProductImageStorage storage){this.categories=categories;this.products=products;this.em=em;this.media=media;this.storage=storage;}
 private String id(){return UUID.randomUUID().toString();}
 private AdminFailure missing(){return new AdminFailure(404,"Recurso no disponible");}
 private Product entity(String id){return products.findById(id).orElseThrow(this::missing);}
 private Category categoryEntity(String id){return categories.findById(id).orElseThrow(this::missing);}
 public CategoryView category(String id){return categoryView(categoryEntity(id));}
 private CategoryView categoryView(Category c){return new CategoryView(c.getId(),c.getName(),c.getSlug(),c.getDescription(),c.isActive(),c.getDisplayOrder());}
 public List<CategoryView> categories(){return categories.findAll(Sort.by("displayOrder","id")).stream().map(this::categoryView).toList();}
 @Transactional public CategoryView saveCategory(String id,CategoryInput r){
  Category c=id==null?new Category():categoryEntity(id);if(id==null)c.setId(id());
  if(categories.existsBySlugAndIdNot(r.slug(),c.getId()))throw new AdminFailure(409,"El slug de categoria ya existe");
  c.setName(r.name().trim());c.setSlug(r.slug());c.setDescription(r.description());c.setActive(r.active());c.setDisplayOrder(r.displayOrder());
  if(id==null)em.persist(c);em.flush();return categoryView(c);
 }
 @Transactional public CategoryView activeCategory(String id,boolean active){var c=categoryEntity(id);c.setActive(active);return categoryView(c);}
 public ProductView product(String id){return view(entity(id));}
 public List<ProductView> products(String category,String search,Boolean published,Boolean featured){
  Specification<Product> spec=(root,q,cb)->cb.conjunction();
  if(category!=null&&!category.isBlank())spec=spec.and((r,q,cb)->cb.equal(r.get("category").get("id"),category));
  if(search!=null&&!search.isBlank()){String literal=search.trim().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_");spec=spec.and((r,q,cb)->cb.like(cb.lower(r.get("name")),"%"+literal+"%",'\\'));}
  if(published!=null)spec=spec.and((r,q,cb)->cb.equal(r.get("published"),published));
  if(featured!=null)spec=spec.and((r,q,cb)->cb.equal(r.get("featured"),featured));
  return products.findAll(spec,Sort.by("displayOrder","id")).stream().map(this::view).toList();
 }
 private ProductView view(Product p){
  var images=p.getImages().stream().filter(ProductImage::isActive).map(this::imageView).toList();String primary=images.stream().filter(ImageView::primaryImage).findFirst().or(()->images.stream().findFirst()).map(ImageView::publicUrl).orElse("");
  return new ProductView(p.getId(),p.getCategory().getId(),categoryView(p.getCategory()),p.getName(),p.getSlug(),p.getShortDescription(),p.getDescription(),p.getSaleType(),p.getUnitLabel(),p.getPackSize(),p.getPackLabel(),p.getMinQuantity(),p.getQuantityStep(),p.isFeatured(),p.isPublished(),p.getDisplayOrder(),p.getSeoTitle(),p.getSeoDescription(),primary,
   p.getMaterials().stream().map(m->new OptionView(m.getId(),m.getName(),m.isActive(),m.getDisplayOrder())).toList(),
   p.getExtras().stream().map(m->new OptionView(m.getId(),m.getName(),m.isActive(),m.getDisplayOrder())).toList(),images);
 }
 @Transactional public ProductView saveProduct(String id,ProductInput r){
  if(r.saleType()==ProductSaleType.PACK){if(r.packSize()==null||r.packLabel()==null||r.packLabel().isBlank())throw new AdminFailure(400,"PACK requiere packSize positivo y packLabel");}
  else if(r.packSize()!=null||r.packLabel()!=null)throw new AdminFailure(400,"QUANTITY y AREA requieren packSize y packLabel null");
  var p=id==null?new Product():entity(id);if(id==null)p.setId(id());
  if(products.existsBySlugAndIdNot(r.slug(),p.getId()))throw new AdminFailure(409,"El slug de producto ya existe");
  p.setCategory(categoryEntity(r.categoryId()));
  p.setName(r.name());
  p.setSlug(r.slug());
  p.setShortDescription(r.shortDescription());
  p.setDescription(r.description());
  p.setSaleType(r.saleType());
  p.setUnitLabel(r.unitLabel());
  p.setPackSize(r.packSize());
  p.setPackLabel(r.packLabel());
  p.setMinQuantity(r.minQuantity());
  p.setQuantityStep(r.quantityStep());
  p.setFeatured(r.featured());
  p.setPublished(r.published());
  p.setDisplayOrder(r.displayOrder());
  p.setSeoTitle(r.seoTitle());
  p.setSeoDescription(r.seoDescription());
  if(id==null)em.persist(p);em.flush();return view(p);
 }
 @Transactional public ProductView published(String id,boolean value){var p=entity(id);p.setPublished(value);return view(p);}
 @Transactional public ProductView featured(String id,boolean value){var p=entity(id);p.setFeatured(value);return view(p);}
 public List<OptionView> materials(String pid){entity(pid);return em.createQuery("select x from ProductMaterial x where x.product.id=:pid order by x.displayOrder,x.id",ProductMaterial.class).setParameter("pid",pid).getResultList().stream().map(x->new OptionView(x.getId(),x.getName(),x.isActive(),x.getDisplayOrder())).toList();}
 private ProductMaterial materialsEntity(String pid,String id){return em.createQuery("select x from ProductMaterial x where x.product.id=:pid and x.id=:id",ProductMaterial.class).setParameter("pid",pid).setParameter("id",id).getResultStream().findFirst().orElseThrow(this::missing);}
 @Transactional public OptionView savematerials(String pid,String id,OptionInput r){
  var p=entity(pid);var x=id==null?new ProductMaterial():materialsEntity(pid,id);if(id==null){x.setId(id());x.setProduct(p);}
  x.setName(r.name().trim());x.setActive(r.active());x.setDisplayOrder(r.displayOrder());if(id==null)em.persist(x);em.flush();return new OptionView(x.getId(),x.getName(),x.isActive(),x.getDisplayOrder());
 }
 @Transactional public OptionView activematerials(String pid,String id,boolean value){var x=materialsEntity(pid,id);x.setActive(value);return new OptionView(x.getId(),x.getName(),x.isActive(),x.getDisplayOrder());}
 public List<OptionView> extras(String pid){entity(pid);return em.createQuery("select x from ProductExtra x where x.product.id=:pid order by x.displayOrder,x.id",ProductExtra.class).setParameter("pid",pid).getResultList().stream().map(x->new OptionView(x.getId(),x.getName(),x.isActive(),x.getDisplayOrder())).toList();}
 private ProductExtra extrasEntity(String pid,String id){return em.createQuery("select x from ProductExtra x where x.product.id=:pid and x.id=:id",ProductExtra.class).setParameter("pid",pid).setParameter("id",id).getResultStream().findFirst().orElseThrow(this::missing);}
 @Transactional public OptionView saveextras(String pid,String id,OptionInput r){
  var p=entity(pid);var x=id==null?new ProductExtra():extrasEntity(pid,id);if(id==null){x.setId(id());x.setProduct(p);}
  x.setName(r.name().trim());x.setActive(r.active());x.setDisplayOrder(r.displayOrder());if(id==null)em.persist(x);em.flush();return new OptionView(x.getId(),x.getName(),x.isActive(),x.getDisplayOrder());
 }
 @Transactional public OptionView activeextras(String pid,String id,boolean value){var x=extrasEntity(pid,id);x.setActive(value);return new OptionView(x.getId(),x.getName(),x.isActive(),x.getDisplayOrder());}
 private ImageView imageView(ProductImage x){return new ImageView(x.getId(),x.getUrl(),media.publicUrl(x.getUrl()),x.getAltText(),x.isPrimaryImage(),x.getDisplayOrder());}
 public List<ImageView> images(String pid){entity(pid);return em.createQuery("select x from ProductImage x where x.product.id=:pid and x.active=true order by x.displayOrder,x.id",ProductImage.class).setParameter("pid",pid).getResultList().stream().map(this::imageView).toList();}
 private ProductImage imageEntity(String pid,String id){return em.createQuery("select x from ProductImage x where x.product.id=:pid and x.id=:id and x.active=true",ProductImage.class).setParameter("pid",pid).setParameter("id",id).getResultStream().findFirst().orElseThrow(this::missing);}
 private Product lockProduct(String pid){var p=entity(pid);em.lock(p,LockModeType.PESSIMISTIC_WRITE);return p;}
 private void clearPrimary(String pid){var all=em.createQuery("select x from ProductImage x where x.product.id=:pid and x.primaryImage=true",ProductImage.class).setParameter("pid",pid).getResultList();all.forEach(x->x.setPrimaryImage(false));em.flush();}
 @Transactional public ImageView saveImage(String pid,String id,ImageInput r){
  try{media.publicUrl(r.url());}catch(IllegalArgumentException ex){throw new AdminFailure(400,"Referencia de imagen invalida: usa HTTP(S) o un path relativo seguro");}
  var p=lockProduct(pid);var x=id==null?new ProductImage():imageEntity(pid,id);
  var existing=images(pid);
  if(id==null && existing.size()>=3)throw new AdminFailure(409,"Este producto ya tiene el máximo de 3 imágenes.");
  boolean primary=r.primaryImage() || existing.stream().noneMatch(ImageView::primaryImage);
  if(primary)clearPrimary(pid);
  if(id!=null && x.getStorageKey()!=null && !x.getUrl().equals(r.url()))
   throw new AdminFailure(400,"Quita la imagen subida antes de reemplazarla");
  if(id==null){x.setId(id());x.setProduct(p);}x.setUrl(r.url());x.setAltText(r.altText());x.setPrimaryImage(primary);x.setDisplayOrder(r.displayOrder());
  if(id==null)em.persist(x);em.flush();return imageView(x);
 }
 @Transactional public ImageView upload(String pid,org.springframework.web.multipart.MultipartFile file,String alt,Integer order,boolean primary) {
  lockProduct(pid);
  var existing=images(pid);
  if(existing.size()>=3)throw new AdminFailure(409,"Este producto ya tiene el máximo de 3 imágenes.");
  if(alt!=null && alt.length()>300)throw new AdminFailure(400,"El texto alternativo admite hasta 300 caracteres");
  if(order!=null && order<0)throw new AdminFailure(400,"El orden debe ser un entero no negativo");
  int next=existing.stream().mapToInt(ImageView::displayOrder).max().orElse(-1);
  if(order==null && next==Integer.MAX_VALUE)throw new AdminFailure(400,"Indica un orden válido");
  var stored=storage.store(pid,file);
  org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
   new org.springframework.transaction.support.TransactionSynchronization(){
    @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)storage.deleteQuietly(stored.key());}
   });
  var result=saveImage(pid,null,new ImageInput(stored.url(),alt==null?"":alt,primary,order==null?next+1:order));
  imageEntity(pid,result.id()).setStorageKey(stored.key());
  return result;
 }
 @Transactional public void removeImage(String pid,String id) {
  lockProduct(pid);var x=imageEntity(pid,id);boolean wasPrimary=x.isPrimaryImage();
  x.setPrimaryImage(false);x.setActive(false);em.flush();
  if(wasPrimary){
   var remaining=images(pid);
   if(!remaining.isEmpty())imageEntity(pid,remaining.getFirst().id()).setPrimaryImage(true);
  }
  String key=x.getStorageKey();
  if(key!=null)org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
   new org.springframework.transaction.support.TransactionSynchronization(){
    @Override public void afterCommit(){storage.deleteQuietly(key);}
   });
 }
 @Transactional public ImageView primary(String pid,String id,boolean value){lockProduct(pid);var x=imageEntity(pid,id);if(value)clearPrimary(pid);x.setPrimaryImage(value);em.flush();return imageView(x);}
}
