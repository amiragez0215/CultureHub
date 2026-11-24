package cn.culturehub.v3;

import java.util.List;

public record CombinationBranch(String id, long user, List<Long> sessions, String trace) {
  public CombinationBranch {
    if (id == null || !id.matches("[0-9a-f-]{36}") || user <= 0 || sessions == null
        || sessions.isEmpty() || sessions.size() > 3 || sessions.stream().anyMatch(s -> s == null || s <= 0)
        || sessions.stream().distinct().count() != sessions.size()) {
      throw new IllegalArgumentException("Invalid combination branch");
    }
    sessions = sessions.stream().sorted().toList();
  }

  public String hold(long session) {
    return "v3-at-" + id + "-" + session;
  }

  public String fallback(long session) {
    return "v3-at-partial-" + id + "-" + session;
  }

  public record Proof(int bookings, int undoRecords) {}
  public record Result(String xid, List<Long> participationIds) {}
  public record Settlement(boolean committed, boolean synced, List<Long> participationIds) {}
  public record Single(String status, Long participationId, String error) {}
}
