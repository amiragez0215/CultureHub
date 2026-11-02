package cn.culturehub;

import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

@Component
public class Jobs {
  final Store s;
  final ParticipationService p;
  final Operations o;
  final Publisher pub;

  public Jobs(Store s, ParticipationService p, Operations o, Publisher pub) {
    this.s = s;
    this.p = p;
    this.o = o;
    this.pub = pub;
  }

  @XxlJob("candidateMaintenance")
  public void maintain() {
    for (var r :
        s.db.queryForList(
            "select p.id from participation p join activity_session x on x.id=p.session_id where"
                + " (p.status='OFFERED' and p.confirm_deadline<=current_timestamp) or"
                + " (p.status='WAITING' and x.registration_end_at<=current_timestamp) order by p.id"
                + " limit 200")) p.expire(Store.id(r, "id"));
  }

  @XxlJob("sessionReminders")
  public void reminders() {
    for (var r :
        s.db.queryForList(
            "select id from activity_session where status='PUBLISHED' and"
                + " start_at>current_timestamp and start_at<=date_add(current_timestamp,interval 30"
                + " minute) order by id")) o.remind(Store.id(r, "id"));
  }

  @XxlJob("outboxPublisher")
  public void publish() {
    pub.publish();
  }
}
