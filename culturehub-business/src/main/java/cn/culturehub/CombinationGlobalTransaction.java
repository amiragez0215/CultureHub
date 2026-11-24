package cn.culturehub;

import cn.culturehub.v3.CombinationBranch;
import java.util.Map;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("v3-p4 & !v3-partner")
class CombinationGlobalTransaction {
  private final Store store;
  private final CombinationTransactions local;
  private final CombinationPeers peers;

  CombinationGlobalTransaction(Store store, CombinationTransactions local, CombinationPeers peers) {
    this.store = store;
    this.local = local;
    this.peers = peers;
  }

  @GlobalTransactional(name = "culturehub-combination", timeoutMills = 15000, rollbackFor = Exception.class)
  public void confirm(String id, String attempt, Map<String, CombinationBranch> branches) {
    String xid = RootContext.getXID();
    if (xid == null) throw new IllegalStateException("Seata did not start an AT transaction");
    // Persist the XID before the first AT branch writes. This record must survive rollback.
    RootContext.unbind();
    try {
      int saved = store.db.update("""
          update combination_request set xid=? where id=? and attempt=? and status='PROCESSING'
            and xid is null and next_attempt_at>now(6)
          """, xid, id, attempt);
      if (saved != 1) throw new IllegalStateException("Combination attempt superseded");
    } finally {
      RootContext.bind(xid);
    }
    for (var entry : branches.entrySet()) {
      var result = "CORE".equals(entry.getKey()) ? local.confirmBranch(entry.getValue())
          : peers.confirm(entry.getKey(), entry.getValue());
      if (result == null || !xid.equals(result.xid()) || result.participationIds().size() != entry.getValue().sessions().size()) {
        throw new IllegalStateException("Invalid AT branch response");
      }
    }
  }
}
