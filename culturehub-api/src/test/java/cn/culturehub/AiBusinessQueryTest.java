package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiBusinessQueryTest {
  private static final String TOKEN="ai-service-test-credential-1234567890";
  @Test void callerCannotSupplyAnotherOwnerAndUnknownIdentityFailsClosed() {
    var queries=mock(GuideQueryService.class);
    var access=mock(Access.class);
    when(access.user()).thenReturn(17L);
    when(queries.activeBookings(17L)).thenReturn(List.of());
    var controller=new AiBusinessQueryController(queries,access,TOKEN);
    var request=new AiBusinessQueryController.Query(AiBusinessQueryController.Tool.MY_ACTIVE_BOOKINGS,null,null,null,null);
    assertThrows(Problem.class,()->controller.query("culturehub-knowledge",TOKEN,request));
    verifyNoInteractions(queries,access);
    controller.query("culturehub-ai",TOKEN,request);
    verify(queries).activeBookings(17L);
  }

  @Test void missingRequiredToolArgumentsNeverReachDataAccess() {
    var queries=mock(GuideQueryService.class);
    var access=mock(Access.class);
    when(access.user()).thenReturn(17L);
    var controller=new AiBusinessQueryController(queries,access,TOKEN);
    var request=new AiBusinessQueryController.Query(AiBusinessQueryController.Tool.SESSION_DETAILS,null,null,null,null);
    assertThrows(Problem.class,()->controller.query("culturehub-ai",TOKEN,request));
    verifyNoInteractions(queries);
  }
}
