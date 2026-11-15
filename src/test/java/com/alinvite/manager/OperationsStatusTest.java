package com.alinvite.manager;

import com.alinvite.commands.OperationAccess;
import com.alinvite.database.ledger.AtomicInviteLedger;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OperationsStatusTest {
    private final QuotaRule normal=QuotaRule.recurring("default",48,1,0);
    private final QuotaRuleSet rules=QuotaRuleSet.of(List.of(normal),"default");
    @Test void fullInventoryDoesNotAdvertiseRetroactive96HourReward() {
        var state=QuotaStatus.from(new AtomicInviteLedger.Inventory(normal.key(),1,2,96L*3600000,0,1,true),rules,true);
        assertEquals("full",state.state()); assertEquals("144.00h",state.next()); assertEquals("48.00h",state.remaining());
        assertEquals(1,state.reserved()); assertEquals("1",state.capacity());
        assertEquals("48.00h",state.interval());
    }
    @Test void statusDistinguishesReadyDisabledUnknownAndOnceFinished() {
        assertEquals("awaiting_settlement",QuotaStatus.from(new AtomicInviteLedger.Inventory(normal.key(),1,1,48L*3600000,0,0,true),rules,true).state());
        assertEquals("disabled",QuotaStatus.from(new AtomicInviteLedger.Inventory(normal.key(),1,1,0,0,0,true),rules,false).state());
        assertEquals("unknown_rule",QuotaStatus.from(new AtomicInviteLedger.Inventory(null,-1,-1,0,0,0,true),rules,true).state());
        var once=new QuotaRule("once",InviteQuotaPolicy.Mode.ONCE,48,1,0);
        var table=QuotaRuleSet.of(List.of(once),"once");
        var ended=QuotaStatus.from(new AtomicInviteLedger.Inventory(once.key(),1,2,48L*3600000,0,0,true),table,true);
        assertEquals("once_finished",ended.state()); assertEquals("-",ended.next());
    }
    @Test void permissionBoundaryDeniesForeignAdministrationAndGateMutations() {
        assertFalse(OperationAccess.allowed(true,true,true,false,false,true));
        assertFalse(OperationAccess.allowed(true,true,true,false,false,false));
        assertFalse(OperationAccess.allowed(false,true,true,false,true,true));
        assertTrue(OperationAccess.allowed(false,true,true,false,true,false));
        assertFalse(OperationAccess.allowed(false,false,true,true,false,false));
        assertFalse(OperationAccess.allowed(false,true,false,false,false,false));
        assertTrue(OperationAccess.allowed(true,false,false,true,false,true));
    }
    @SuppressWarnings("unchecked")
    @Test void shippedConfigurationAndBothLanguagesCoverOperationalStates() throws Exception {
        Yaml yaml=new Yaml();
        try(var stream=getClass().getResourceAsStream("/config.yml")) {
            Map<String,Object> config=yaml.load(stream);
            assertEquals(20,((Map<String,Object>)config.get("pre_invites")).get("expiry_hours"));
        }
        for(String lang:List.of("zh_cn","en_us")) try(var stream=getClass().getResourceAsStream("/languages/"+lang+".yml")) {
            Map<String,Object> root=yaml.load(stream);
            Map<String,Object> ops=(Map<String,Object>)root.get("operations");
            for(String key:List.of("status","progress","disabled","full","once_finished","awaiting_settlement","unknown_rule","unregistered","pending_row","delivery_detail","resolve_rejected")) assertNotNull(ops.get(key),lang+":"+key);
            assertTrue(ops.get("status").toString().contains("{interval}"));
            assertFalse(((Map<String,Object>)root.get("errors")).get("invite_quota_exhausted").toString().contains("24"));
            assertTrue(((Map<String,Object>)root.get("commands")).get("admin") instanceof Map);
        }
        try(var stream=getClass().getResourceAsStream("/plugin.yml")) {
            Map<String,Object> root=yaml.load(stream);
            var permissions=(Map<String,Object>)root.get("permissions");
            assertEquals("op",((Map<String,Object>)permissions.get("alinvite.admin")).get("default"));
            assertEquals(true,((Map<String,Object>)permissions.get("alinvite.pending")).get("default"));
        }
    }
}
