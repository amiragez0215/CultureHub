package cn.culturehub.ai.guide;

import cn.culturehub.Problem;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Native 1.1.2.0 CheckpointSaver SPI backed by the existing Lettuce template. */
@org.springframework.context.annotation.Profile("!v3-p5 & !v3-partner")
@Component
public class LettuceGuideSaver implements BaseCheckpointSaver {
  record Frame(String id, String node, String next, GuideState.Turn guide) {}

  final StringRedisTemplate redis;
  final ObjectMapper json;

  public LettuceGuideSaver(StringRedisTemplate redis, ObjectMapper json) {
    this.redis = redis;
    this.json = json;
  }

  String key(RunnableConfig config) {
    String id = config.threadId().orElseThrow();
    if (!id.matches("[0-9]+:[a-f0-9-]{36}:[a-f0-9-]{36}"))
      throw new IllegalArgumentException("Invalid authenticated guide thread");
    return "ai:v2:native:" + id;
  }

  public Collection<Checkpoint> list(RunnableConfig config) {
    try {
      var values = redis.opsForList().range(key(config), 0, 31);
      var result = new ArrayList<Checkpoint>();
      if (values != null)
        for (String raw : values) {
          var frame = json.readValue(raw, Frame.class);
          result.add(
              Checkpoint.builder()
                  .id(frame.id())
                  .nodeId(frame.node())
                  .nextNodeId(frame.next())
                  .state(Map.of("guide", frame.guide()))
                  .build());
        }
      return result;
    } catch (Exception error) {
      throw new Problem("AI_MEMORY_UNAVAILABLE", "规划检查点不可用，请稍后重试；未执行预约", 503);
    }
  }

  public Optional<Checkpoint> get(RunnableConfig config) {
    return list(config).stream()
        .filter(
            c -> config.checkPointId().isEmpty() || config.checkPointId().get().equals(c.getId()))
        .findFirst();
  }

  public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
    try {
      String key = key(config);
      var frame =
          new Frame(
              checkpoint.getId(),
              checkpoint.getNodeId(),
              checkpoint.getNextNodeId(),
              (GuideState.Turn) checkpoint.getState().get("guide"));
      redis.opsForList().leftPush(key, json.writeValueAsString(frame));
      redis.opsForList().trim(key, 0, 31);
      redis.expire(key, Duration.ofMinutes(30));
      return RunnableConfig.builder(config).checkPointId(checkpoint.getId()).build();
    } catch (Exception error) {
      var problem = new Problem("AI_MEMORY_UNAVAILABLE", "无法保存规划检查点；未执行预约", 503);
      problem.initCause(error);
      throw problem;
    }
  }

  public Tag release(RunnableConfig config) {
    var checkpoints = list(config);
    redis.delete(key(config));
    return new Tag(config.threadId().orElseThrow(), checkpoints);
  }
}
