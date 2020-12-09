package worker;

import adapters.SQSAdapter;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import protocol.MessageProtocol;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.UUID;

public class Worker {

  private static final ITesseract tesseract = new Tesseract();

  private static final Region REGION = Region.US_EAST_1;
  private static final String TESSERACT_PATH = "/usr/share/tesseract-ocr/4.00/tessdata";

  private static SqsClient sqs;

  private static SQSAdapter taskQueue;
  private static SQSAdapter responseQueue;

  public static void main(String[] args) {
    sqs = SqsClient.builder().region(REGION).build();
    tesseract.setDatapath(TESSERACT_PATH);
    tesseract.setLanguage("eng");

    String managerTaskQueueName = args[0];
    String workerResponseQueueName = args[1];

    taskQueue = new SQSAdapter(sqs, managerTaskQueueName, false);
    responseQueue = new SQSAdapter(sqs, workerResponseQueueName, false);
    start();
  }

  public static void start() {
    for (; ; ) {
      try {
        taskQueue.receiveMessage(
            message -> {
              String[] messageSplits = MessageProtocol.split(message.body());
              UUID appId = UUID.fromString(messageSplits[1]);
              String imageURL = messageSplits[2];
              handleWorkerMessage(appId, imageURL);
              taskQueue.deleteMessage(message);
            });
      } catch (Exception e) {
        responseQueue.sendMessage(MessageProtocol.createWorkerFailMessage(e.getMessage()));
      }
    }
  }

  private static void handleWorkerMessage(UUID appId, String imageURL) {
    try {
      String OCRResult = getOCRFromImageURL(imageURL);
      String response = MessageProtocol.createWorkerResponse(appId, imageURL, OCRResult);
      responseQueue.sendMessage(response);
    } catch (TesseractException | IOException e) {
      String response =
          MessageProtocol.createWorkerResponse(
              appId, imageURL, MessageProtocol.ERROR_PREFIX + e.getMessage());
      responseQueue.sendMessage(response);
    }
  }

  private static String getOCRFromImageURL(String imageURL) throws IOException, TesseractException {
    BufferedImage imageBuffer = ImageIO.read(new URL(imageURL));
    return tesseract.doOCR(imageBuffer);
  }
}
