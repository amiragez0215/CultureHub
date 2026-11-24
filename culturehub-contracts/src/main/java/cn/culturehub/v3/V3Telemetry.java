package cn.culturehub.v3;
import io.micrometer.core.instrument.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
/** Read-only backlog metrics. A failed sample is NaN, never a fabricated zero. */
@AutoConfiguration
@ConditionalOnProperty(name="v3.observability.enabled",havingValue="true")
public class V3Telemetry {
 @Bean ApplicationRunner businessBacklogs(ObjectProvider<DataSource> sources,MeterRegistry registry){return args->{
  var source=sources.getIfAvailable();if(source==null)return;
  try(var connection=source.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("select table_name from information_schema.tables where table_schema=database()")){
   var names=new HashSet<String>();while(rows.next())names.add(rows.getString(1));
   Map<String,Map<String,String>> tables=Map.of(
    "booking_request",Map.of("pending","state in ('RECEIVED','QUEUED','PROCESSING')","expired","state='EXPIRED'","rejected","state='REJECTED'"),
    "combination_request",Map.of("pending","status in ('RECEIVED','PROCESSING','SETTLING','FALLBACK')",
        "manual","status='MANUAL_INTERVENTION'","settling","status='SETTLING'"),
    "ingestion_job",Map.of("pending","status not in ('SUCCEEDED','FAILED')","failed","status='FAILED'"),
    "outbox",Map.of("pending","status='PENDING'"));
   tables.forEach((table,states)->{if(names.contains(table))states.forEach((state,condition)->Gauge.builder("culturehub.backlog",source,s->count(s,"select count(*) from "+table+" where "+condition)).tags("table",table,"state",state).register(registry));});
  }
 };}
 private static double count(DataSource source,String query){try(var c=source.getConnection();var s=c.createStatement()){s.setQueryTimeout(2);try(var r=s.executeQuery(query)){return r.next()?r.getDouble(1):Double.NaN;}}catch(Exception unavailable){return Double.NaN;}}
}
