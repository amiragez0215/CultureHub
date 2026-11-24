package cn.culturehub;

import cn.culturehub.v3.CombinationBranch;
import cn.culturehub.v3.ServiceBoundary;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.seata.core.context.RootContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("v3-p4 & !v3-partner")
class CombinationController {
  private final CombinationBooking bookings;
  private final CombinationPeers peers;
  private final Store store;
  private final Access access;
  private final boolean demo;

  CombinationController(CombinationBooking bookings, CombinationPeers peers, Store store, Access access,
      @Value("${V3_DEMO_ENABLED:false}") boolean demo) {
    this.bookings = bookings;
    this.peers = peers;
    this.store = store;
    this.access = access;
    this.demo = demo;
  }

  @PostMapping("/api/v1/combinations")
  Object submit(@RequestBody CombinationBooking.Submission submission) {
    return bookings.submit(access.user(), submission, false);
  }

  @PostMapping("/api/v1/demo/combinations")
  Object submitDemo(@RequestBody CombinationBooking.Submission submission) {
    requireDemo();
    return bookings.submit(access.user(), submission, true);
  }

  @GetMapping("/api/v1/combinations")
  Object history(@RequestParam(defaultValue = "1") int page) {
    return bookings.history(access.user(), page);
  }

  @GetMapping("/api/v1/combinations/{id}")
  Object status(@PathVariable String id) {
    return bookings.get(id, access.user());
  }

  @GetMapping("/api/v1/combinations/by-request/{request}")
  Object byRequest(@PathVariable String request) {
    return bookings.byRequest(request, access.user());
  }

  @GetMapping("/api/v1/demo/combinations/catalog")
  Object catalog() {
    requireDemo();
    var groups = new ArrayList<Map<String, Object>>();
    groups.add(Map.of("participant", "CORE", "status", "SUCCESS", "sessions", available(store)));
    for (String name : List.of("PARTNER_A", "PARTNER_B")) {
      try {
        groups.add(Map.of("participant", name, "status", "SUCCESS", "sessions", peers.catalog(name)));
      } catch (RuntimeException unavailable) {
        groups.add(Map.of("participant", name, "status", "UNAVAILABLE", "sessions", List.of()));
      }
    }
    return Map.of("demoOnly", true, "participants", groups);
  }

  private void requireDemo() {
    if (!demo) throw new Problem("NOT_FOUND", "Combination demo is disabled", 404);
  }

  static Object available(Store store) {
    return store.db.queryForList("""
        select x.id sessionId,a.id activityId,a.title,v.id venueId,v.name venueName,x.start_at startAt,x.end_at endAt,
        x.capacity-x.occupied_count remaining from activity_session x join activity a on a.id=x.activity_id
        join venue v on v.id=a.venue_id where x.status='PUBLISHED' and a.status='PUBLISHED' and v.status='OPEN'
        and x.registration_start_at<=now(6) and x.registration_end_at>now(6) and x.start_at>now(6)
        order by x.start_at,x.id limit 30
        """);
  }
}

@RestController
@Profile("v3-partner")
@RequestMapping("/internal/v1/combination")
class CombinationBranchController {
  private final CombinationParticipant participant;
  private final CombinationTransactions transactions;
  private final Store store;
  private final String credential;

  CombinationBranchController(CombinationParticipant participant, CombinationTransactions transactions, Store store,
      @Value("${v3.internal.combination}") String credential) {
    this.participant = participant;
    this.transactions = transactions;
    this.store = store;
    this.credential = ServiceBoundary.requireCredential(credential);
  }

  @GetMapping("/catalog")
  Object catalog(@RequestHeader(name = ServiceBoundary.CALLER, required = false) String caller,
      @RequestHeader(name = ServiceBoundary.CREDENTIAL, required = false) String token) {
    authorize(caller, token);
    return CombinationController.available(store);
  }

  @PostMapping("/{action}")
  Object branch(@PathVariable String action, @RequestBody CombinationBranch request,
      @RequestHeader(name = ServiceBoundary.CALLER, required = false) String caller,
      @RequestHeader(name = ServiceBoundary.CREDENTIAL, required = false) String token,
      @RequestHeader(name = RootContext.KEY_XID, required = false) String xid,
      @RequestParam(name = "xid", required = false) String proofXid,
      @RequestParam(defaultValue = "false") boolean committed) {
    authorize(caller, token);
    if ("confirm".equals(action)) {
      Problem.require(xid != null && !xid.isBlank() && xid.length() <= 128
          && (RootContext.getXID() == null || xid.equals(RootContext.getXID())), "SEATA_XID_REQUIRED", "Invalid XID");
      boolean boundHere = RootContext.getXID() == null;
      if (boundHere) RootContext.bind(xid);
      try {
        return transactions.confirmBranch(request);
      } finally {
        if (boundHere) RootContext.unbind();
      }
    }
    RootContext.assertNotInGlobalTransaction();
    return switch (action) {
      case "prepare" -> { participant.prepare(request); yield null; }
      case "pin" -> { participant.pin(request); yield null; }
      case "proof" -> {
        Problem.require(proofXid != null && !proofXid.isBlank() && proofXid.length() <= 128,
            "SEATA_XID_REQUIRED", "Invalid XID");
        yield participant.proof(request, proofXid);
      }
      case "settle" -> participant.settle(request, committed);
      case "fallback" -> {
        Problem.require(request.sessions().size() == 1, "VALIDATION_FAILED", "One fallback session required");
        yield participant.fallback(request);
      }
      default -> throw new Problem("NOT_FOUND", "Unknown combination action", 404);
    };
  }

  private void authorize(String caller, String token) {
    if (!"culturehub-core".equals(caller) || !ServiceBoundary.authenticates(credential, token)) {
      throw new Problem("SERVICE_UNAUTHENTICATED", "Invalid service credentials", 401);
    }
  }
}
