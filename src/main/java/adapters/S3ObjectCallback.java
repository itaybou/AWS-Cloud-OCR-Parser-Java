package adapters;

import software.amazon.awssdk.services.s3.model.S3Object;

public interface S3ObjectCallback {
  void objectAction(S3Object message);
}
