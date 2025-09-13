package gcfv2storage;

import com.google.cloud.functions.CloudEventsFunction;
import io.cloudevents.CloudEvent;
import java.util.logging.Logger;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.google.cloud.vision.v1.*;
import com.google.protobuf.ByteString;

import com.google.cloud.aiplatform.v1.*;
import com.google.protobuf.Value;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.ListValue;

import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

public class StorageFunction implements CloudEventsFunction {
  private static final Logger logger = Logger.getLogger(StorageFunction.class.getName());

  @Override
  public void accept(CloudEvent event) {
    // CloudEventのdataをJSONとしてパース
    String jsonData = new String(event.getData().toBytes());
    JsonObject data = JsonParser.parseString(jsonData).getAsJsonObject();

    // バケット名とオブジェクト名を取得
    String bucket = data.get("bucket").getAsString();
    String name = data.get("name").getAsString();

    logger.info("アップロードされた画像: " + name + "（バケット: " + bucket + "）");

    // Vision API → Vertex AI 呼び出し
    analyzeImageAndSuggest(bucket, name);
  }

  private void analyzeImageAndSuggest(String bucket, String name) {
    try {
      // ① Cloud Storageから画像取得
      String imageUrl = "https://storage.googleapis.com/" + bucket + "/" + name;
      InputStream inputStream = new URL(imageUrl).openStream();
      byte[] imageBytes = inputStream.readAllBytes();

      // ② Vision APIで牌認識（OCR）
      List<String> tiles = recognizeTiles(imageBytes);
      logger.info("認識された牌: " + String.join(", ", tiles));

      // ③ Vertex AIで打牌提案
      String suggestion = predictTile(tiles);
      logger.info("打牌提案: " + suggestion);

    } catch (Exception e) {
      logger.severe("画像解析または打牌提案に失敗: " + e.getMessage());
    }
  }

  private List<String> recognizeTiles(byte[] imageBytes) throws Exception {
    try (ImageAnnotatorClient visionClient = ImageAnnotatorClient.create()) {
      ByteString byteString = ByteString.copyFrom(imageBytes);
      Image image = Image.newBuilder().setContent(byteString).build();
      com.google.cloud.vision.v1.Feature feature = com.google.cloud.vision.v1.Feature.newBuilder().setType(com.google.cloud.vision.v1.Feature.Type.TEXT_DETECTION).build();
      AnnotateImageRequest request = AnnotateImageRequest.newBuilder()
        .addFeatures(feature)
        .setImage(image)
        .build();

      AnnotateImageResponse response = visionClient.batchAnnotateImages(List.of(request)).getResponses(0);
      String rawText = response.getTextAnnotations(0).getDescription();

      // 仮の牌抽出（空白区切り）
      return List.of(rawText.split("\\s+"));
    }
  }

  private String predictTile(List<String> tiles) throws Exception {
    try (PredictionServiceClient client = PredictionServiceClient.create()) {
      EndpointName endpoint = EndpointName.of("your-project-id", "asia-northeast1", "your-endpoint-id");

     // tiles情報をStructに格納
Struct structPayload = Struct.newBuilder()
  .putFields("tiles", Value.newBuilder().setStringValue(String.join(" ", tiles)).build())
  .build();

// StructをValueにラップ
Value payload = Value.newBuilder()
  .setStructValue(structPayload)
  .build();

// PredictRequestに渡す
PredictRequest predictRequest = PredictRequest.newBuilder()
  .setEndpoint(endpoint.toString())
  .setParameters(payload) // ✅ Value型に変更
  .build();

      PredictResponse response = client.predict(predictRequest);
      return response.getPredictions(0).getStructValue().getFieldsOrThrow("suggestion").getStringValue();
    }
  }
}
