package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;

public interface GuideLanguage {
  IntentPatch understand(Turn state);

  Proposal generate(Turn state);
}
