package adapters;

import software.amazon.awssdk.services.sqs.model.Message;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

public interface MessageReceivedCallback {
  void onReceive(Message message) throws IOException, ExecutionException, InterruptedException;
}
