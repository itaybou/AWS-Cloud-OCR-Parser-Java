package adapters;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.io.InputStream;
import java.nio.file.Paths;

public class S3Adapter {

  private S3Client s3;

  public S3Adapter(Region region) {
    s3 = S3Client.builder().region(region).build();
  }

  public InputStream getFileStream(String bucketName, String key) {
    try {
      GetObjectRequest objectRequest =
          GetObjectRequest.builder().bucket(bucketName).key(key).build();
      return s3.getObject(objectRequest, ResponseTransformer.toBytes()).asInputStream();
    } catch (Exception e) {
      return null;
    }
  }

  public void uploadString(String bucketName, String key, String content) {
    try {
      PutObjectRequest putObjectRequest =
          PutObjectRequest.builder()
              .bucket(bucketName)
              .key(key)
              .acl(ObjectCannedACL.PUBLIC_READ_WRITE)
              .build();
      s3.putObject(putObjectRequest, RequestBody.fromString(content));
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public void createBucketAndUploadFile(String bucketName, String filePath) {
    try {
      createBucket(bucketName);
      PutObjectRequest putObjectRequest =
          PutObjectRequest.builder()
              .bucket(bucketName)
              .key(filePath)
              .acl(ObjectCannedACL.PUBLIC_READ_WRITE)
              .build();
      s3.putObject(putObjectRequest, Paths.get(filePath));
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void createBucket(String bucketName) {
    CreateBucketRequest bucketRequest = CreateBucketRequest.builder().bucket(bucketName).build();
    s3.createBucket(bucketRequest);
  }

  public void deleteObject(String bucketName, String key) {
    DeleteObjectRequest deleteObjectRequest =
        DeleteObjectRequest.builder().bucket(bucketName).key(key).build();
    s3.deleteObject(deleteObjectRequest);
  }

  public void streamBucket(String bucketName, S3ObjectCallback callback) {
    ListObjectsV2Request listObjectRequest =
        ListObjectsV2Request.builder().bucket(bucketName).build();
    ListObjectsV2Iterable listObjectResponse = s3.listObjectsV2Paginator(listObjectRequest);
    listObjectResponse.stream().forEach(page -> page.contents().forEach(callback::objectAction));
  }

  public void deleteBucket(String bucketName) {
    try {
      streamBucket(bucketName, object -> deleteObject(bucketName, object.key()));
      DeleteBucketRequest deleteBucketRequest =
          DeleteBucketRequest.builder().bucket(bucketName).build();
      s3.deleteBucket(deleteBucketRequest);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
