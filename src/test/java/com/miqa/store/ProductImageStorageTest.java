package com.miqa.store;

import com.miqa.store.admin.*;
import com.miqa.store.config.MediaProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class ProductImageStorageTest {
 @TempDir Path root;
 ProductImageStorage storage;
 // A real 1x1 WebP fixture; no decoding/conversion dependency required.
 byte[] webp=Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
 @BeforeEach void setup(){
  var properties=new MediaProperties();properties.setStoragePath(root);properties.setBaseUrl("https://api.example.test/media");
  storage=new ProductImageStorage(properties);
 }
 @Test void acceptsWebpAndDeletesOnlyGeneratedKeys()throws Exception{
  var result=storage.store("product-1",new MockMultipartFile("file","image.webp","image/webp",webp));
  assertThat(Files.readAllBytes(root.resolve(result.key()))).isEqualTo(webp);
  storage.deleteQuietly(result.key());assertThat(Files.exists(root.resolve(result.key()))).isFalse();
 }
 @Test void rejectsTraversalWithoutWritingOutsideStorage()throws Exception{
  assertThatThrownBy(()->storage.store("../outside",new MockMultipartFile("file","image.webp","image/webp",webp))).isInstanceOf(AdminFailure.class);
  Path legacy=root.resolve("legacy.png");Files.writeString(legacy,"legacy");
  storage.deleteQuietly("../legacy.png");storage.deleteQuietly("/images/products/legacy.png");storage.deleteQuietly(null);
  assertThat(Files.readString(legacy)).isEqualTo("legacy");
  try(var paths=Files.list(root)){assertThat(paths.toList()).containsExactly(legacy);}
 }
 @Test void rejectsInvalidWebpSignature(){
  assertThatThrownBy(()->storage.store("p1",new MockMultipartFile("file","image.webp","image/webp",new byte[20]))).isInstanceOf(AdminFailure.class);
 }
}
