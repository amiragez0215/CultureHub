package cn.culturehub;

import cn.culturehub.ai.guide.GuideAgentV2;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai/conversations")
public class GuidePlanController {
  final GuideAgentV2 agent;

  public GuidePlanController(GuideAgentV2 agent) {
    this.agent = agent;
  }

  @GetMapping
  Object conversations() {
    return agent.conversations();
  }

  @GetMapping("/{id}/saved-plan")
  Object recover(@PathVariable String id) {
    return agent.recover(id);
  }
}
