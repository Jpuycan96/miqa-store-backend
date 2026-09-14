package com.miqa.store;

import com.miqa.store.admin.*;
import com.miqa.store.config.MediaProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
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
 @Test void makesNewAndExistingManagedDirectoriesAndFilesReadableOnPosix()throws Exception{
  Assumptions.assumeTrue(Files.getFileAttributeView(root,PosixFileAttributeView.class)!=null,"Filesystem has no POSIX permissions");
  Path mediaRoot=root.resolve("media");
  var properties=new MediaProperties();properties.setStoragePath(mediaRoot);properties.setBaseUrl("https://api.example.test/media");
  storage=new ProductImageStorage(properties);
  var ancestorPermissions=Files.getPosixFilePermissions(root);
  for(int upload=0;upload<2;upload++){
   var result=storage.store("product-1",new MockMultipartFile("file","image.webp","image/webp",webp));
   assertThat(Files.getPosixFilePermissions(mediaRoot.resolve(result.key()))).isEqualTo(PosixFilePermissions.fromString("rw-r--r--"));
   for(Path directory:new Path[]{mediaRoot,mediaRoot.resolve("products"),mediaRoot.resolve("products/product-1")}){
    assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
    Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwxr-x---"));
   }
   assertThat(Files.getPosixFilePermissions(root)).isEqualTo(ancestorPermissions);
  }
 }
 @Test void rejectsSymlinkBeforeChangingPermissionsOrWritingOnPosix()throws Exception{
  Assumptions.assumeTrue(Files.getFileAttributeView(root,PosixFileAttributeView.class)!=null,"Filesystem has no POSIX permissions");
  Path outside=Files.createDirectory(root.resolve("outside"));
  Files.setPosixFilePermissions(outside,PosixFilePermissions.fromString("rwx------"));
  Files.createSymbolicLink(root.resolve("products"),outside);
  assertThatThrownBy(()->storage.store("product-1",new MockMultipartFile("file","image.webp","image/webp",webp))).isInstanceOf(AdminFailure.class);
  assertThat(Files.getPosixFilePermissions(outside)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
  try(var paths=Files.list(outside)){assertThat(paths.toList()).isEmpty();}
 }
 @Test void rejectsInvalidWebpSignature(){
  assertThatThrownBy(()->storage.store("p1",new MockMultipartFile("file","image.webp","image/webp",new byte[20]))).isInstanceOf(AdminFailure.class);
 }
}
