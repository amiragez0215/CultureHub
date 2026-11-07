package cn.culturehub.ai.guide;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.serializer.StateSerializer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.*;

/**
 * Preserve typed nested records across 1.1.2.0 checkpoint cloning; no polymorphic class loading.
 */
public class GuideSerializer extends StateSerializer {
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  public GuideSerializer() {
    super(OverAllState::new);
  }

  public void writeData(Map<String, Object> data, ObjectOutput output) throws IOException {
    byte[] bytes = json.writeValueAsBytes(data);
    if (bytes.length > 2_000_000) throw new IOException("Guide state too large");
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  public Map<String, Object> readData(ObjectInput input) throws IOException {
    int length = input.readInt();
    if (length < 0 || length > 2_000_000) throw new IOException("Invalid guide state size");
    byte[] bytes = new byte[length];
    input.readFully(bytes);
    Map<String, Object> values =
        json.readValue(bytes, new TypeReference<LinkedHashMap<String, Object>>() {});
    if (values.containsKey("guide"))
      values.put("guide", json.convertValue(values.get("guide"), GuideState.Turn.class));
    return values;
  }
}
