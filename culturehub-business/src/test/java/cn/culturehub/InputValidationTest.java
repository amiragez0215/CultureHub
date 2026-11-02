package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class InputValidationTest {
  @Test
  void missingAndWrongInputsAre400() {
    for (Map<String, Object> value :
        List.<Map<String, Object>>of(
            Map.of(), Map.of("n", "hello"), Map.of("n", 1.5), Map.of("n", -1))) {
      var e = assertThrows(Problem.class, () -> Inputs.number(value, "n"));
      assertEquals(400, e.status);
    }
    assertEquals(0, Store.id(Map.of("occupied", 0), "occupied"));
  }

  @Test
  void timeAndCodesAreValidated() {
    assertEquals(400, assertThrows(Problem.class, () -> Inputs.time(Map.of(), "start_at")).status);
    assertEquals(
        400,
        assertThrows(Problem.class, () -> Inputs.time(Map.of("start_at", "bad"), "start_at"))
            .status);
    assertThrows(
        Problem.class,
        () -> Inputs.code(Map.of("category", "艺术体验"), "category", "ART", Set.of("ART")));
    assertEquals(
        "ART", Inputs.code(Map.of("category", "ART"), "category", "CULTURE", Set.of("ART")));
  }

  @Test
  void invalidPaginationAndBlankText() {
    assertThrows(Problem.class, () -> Inputs.offset(0));
    assertThrows(Problem.class, () -> Inputs.offset(Integer.MAX_VALUE));
    assertThrows(Problem.class, () -> Inputs.text(Map.of("name", "   "), "name", 120));
    assertThrows(Problem.class, () -> Inputs.optionalId(Map.of("id", "bad")));
  }
}
