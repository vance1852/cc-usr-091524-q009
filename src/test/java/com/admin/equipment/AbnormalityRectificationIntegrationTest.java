package com.admin.equipment;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.AbnormalityCorrectiveAction;
import com.admin.equipment.model.inspection.InspectionAbnormality;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.repo.AppUserRepository;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.inspection.AbnormalityCorrectiveActionRepository;
import com.admin.equipment.repo.inspection.CorrectiveActionEventRepository;
import com.admin.equipment.repo.inspection.AbnormalityEventRepository;
import com.admin.equipment.repo.inspection.InspectionAbnormalityRepository;
import com.admin.equipment.repo.inspection.InspectionTaskRepository;
import com.admin.equipment.security.PasswordUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异常整改闭环端到端集成测试（H2 + MySQL 方言模式 + 真实 Servlet 容器）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AbnormalityRectificationIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private EquipmentRepository equipmentRepo;
    @Autowired
    private AppUserRepository userRepo;
    @Autowired
    private WorkOrderRepository workOrderRepo;
    @Autowired
    private InspectionTaskRepository taskRepo;
    @Autowired
    private InspectionAbnormalityRepository abnormalityRepo;
    @Autowired
    private AbnormalityCorrectiveActionRepository actionRepo;
    @Autowired
    private CorrectiveActionEventRepository actionEventRepo;
    @Autowired
    private AbnormalityEventRepository abEventRepo;

    private String token;

    @BeforeEach
    void setUp() {
        actionEventRepo.deleteAll();
        abEventRepo.deleteAll();
        actionRepo.deleteAll();
        abnormalityRepo.deleteAll();
        workOrderRepo.deleteAll();
        taskRepo.deleteAll();
        equipmentRepo.deleteAll();
        if (!userRepo.existsByUsername("admin")) {
            AppUser u = new AppUser();
            u.setUsername("admin");
            u.setPasswordHash(PasswordUtil.hash("admin123"));
            u.setDisplayName("平台管理员");
            userRepo.save(u);
        }
        Map<?, ?> login = rest.postForObject("http://localhost:" + port + "/api/auth/login",
                Map.of("username", "admin", "password", "admin123"), Map.class);
        token = (String) login.get("access_token");
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    private ResponseEntity<String> post(String path, Object body) {
        return rest.exchange("http://localhost:" + port + path, HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), String.class);
    }

    private ResponseEntity<String> patch(String path, Object body) {
        return rest.exchange("http://localhost:" + port + path, HttpMethod.PATCH,
                new HttpEntity<>(body, jsonHeaders()), String.class);
    }

    private ResponseEntity<String> get(String path) {
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()), String.class);
    }

    private JsonNode json(ResponseEntity<String> r) throws Exception {
        assertThat(r.getStatusCode().is2xxSuccessful())
                .as("请求应成功：%s %s", r.getStatusCode(), r.getBody()).isTrue();
        return om.readTree(r.getBody());
    }

    private Equipment newEquipment(String code) {
        Equipment e = new Equipment();
        e.setCode(code);
        e.setName(code + "-设备");
        e.setLocation("动力站");
        e.setType("pump");
        e.setStatus("warning");
        return equipmentRepo.save(e);
    }

    private InspectionTask newTask() {
        InspectionTask t = new InspectionTask();
        t.setPlanId(1L);
        t.setTemplateId(1L);
        t.setCode("TK-TEST-" + System.nanoTime());
        t.setStatus("in_progress");
        return taskRepo.save(t);
    }

    private WorkOrder newWorkOrder(Long equipmentId, String status) {
        WorkOrder w = new WorkOrder();
        w.setEquipmentId(equipmentId);
        w.setTitle("[巡检转工单] 测试");
        w.setType("repair");
        w.setPriority("high");
        w.setStatus(status);
        w.setAssignee("张维保");
        return workOrderRepo.save(w);
    }

    private InspectionAbnormality newAbnormality(InspectionTask t, Equipment e, WorkOrder wo,
                                                  String severity) {
        InspectionAbnormality ab = new InspectionAbnormality();
        ab.setTaskId(t.getId());
        ab.setTaskPointId(0L);
        ab.setPointId(0L);
        ab.setEquipmentId(e == null ? null : e.getId());
        ab.setEquipmentCode(e == null ? "" : e.getCode());
        ab.setEquipmentName(e == null ? "" : e.getName());
        ab.setItemName("振动情况");
        ab.setTitle("泵振动异常");
        ab.setDescription("振动值超标");
        ab.setSeverity(severity);
        ab.setStatus("wo_created");
        if (wo != null) {
            ab.setWorkOrderId(wo.getId());
            ab.setWorkOrderCreated(true);
        }
        return abnormalityRepo.save(ab);
    }

    private long createAction(Long abId, String category, String title, String owner,
                              LocalDateTime due, List<Long> preIds) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("category", category);
        body.put("title", title);
        body.put("content", title + "内容");
        body.put("ownerName", owner);
        body.put("dueDate", due == null ? null : due.toString());
        body.put("prerequisiteIds", preIds);
        body.put("actor", "质量主管");
        ResponseEntity<String> r = post("/api/inspection/abnormalities/" + abId + "/actions", body);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        return JsonPath.read(r, "/id").asLong();
    }

    private static final String BASE = "/api/inspection";

    @Test
    void fullHappyPath_closesOnlyWhenEveryConditionMet() throws Exception {
        Equipment e = newEquipment("EQ-IT-1");
        InspectionTask t = newTask();
        WorkOrder wo = newWorkOrder(e.getId(), "open");
        InspectionAbnormality ab = newAbnormality(t, e, wo, "medium");

        long a1 = createAction(ab.getId(), "temporary", "加装临时减振垫", "王巡检",
                LocalDateTime.now().plusDays(1), List.of());
        long a2 = createAction(ab.getId(), "long_term", "更换泵轴承并重做对中", "赵工程师",
                LocalDateTime.now().plusDays(7), List.of(a1));

        // 前置措施未完成，不能提交长期措施
        ResponseEntity<String> blocked = post(BASE + "/abnormalities/actions/" + a2 + "/submit",
                Map.of("evidence", "已更换轴承", "actor", "赵工程师"));
        assertThat(blocked.getStatusCode().value()).isEqualTo(409);

        // 负责人提交，非负责人不能代为提交
        ResponseEntity<String> wrongOwner = post(BASE + "/abnormalities/actions/" + a1 + "/submit",
                Map.of("evidence", "减振垫照片http://x/1.jpg", "actor", "李四"));
        assertThat(wrongOwner.getStatusCode().value()).isEqualTo(422);

        assertThat(post(BASE + "/abnormalities/actions/" + a1 + "/submit",
                Map.of("evidence", "减振垫照片http://x/1.jpg", "actor", "王巡检")).getStatusCode().is2xxSuccessful()).isTrue();

        // 提交人不能验证自己的措施
        ResponseEntity<String> selfVerify = post(BASE + "/abnormalities/actions/" + a1 + "/verify",
                Map.of("pass", true, "comment", "ok", "actor", "王巡检"));
        assertThat(selfVerify.getStatusCode().value()).isEqualTo(422);

        // 由不同人员验证通过
        JsonNode v1 = json(post(BASE + "/abnormalities/actions/" + a1 + "/verify",
                Map.of("pass", true, "comment", "减振措施有效", "actor", "陈质量")));
        assertThat(v1.get("status").asText()).isEqualTo("verified");
        assertThat(v1.get("verifiedBy").asText()).isEqualTo("陈质量");
        assertThat(v1.get("currentRound").asInt()).isEqualTo(1);

        // 长期措施：提交→验证
        assertThat(post(BASE + "/abnormalities/actions/" + a2 + "/submit",
                Map.of("evidence", "轴承更换记录WO-2", "actor", "赵工程师")).getStatusCode().is2xxSuccessful()).isTrue();
        json(post(BASE + "/abnormalities/actions/" + a2 + "/verify",
                Map.of("pass", true, "comment", "对中合格", "actor", "陈质量")));

        // 复检通过，但工单未完成 → 仍然不闭环
        json(post(BASE + "/tasks/abnormality/recheck",
                Map.of("abnormalityId", ab.getId(), "result", "passed", "recheckBy", "陈质量")));
        JsonNode beforeWo = json(get(BASE + "/abnormalities/" + ab.getId() + "/rectification"));
        assertThat(beforeWo.get("closedLoop").asBoolean()).isFalse();
        assertThat(toStringList(beforeWo.get("pendingReasons"))).anyMatch(s -> s.contains("工单"));

        // 工单 done → 全部条件满足，闭环
        json(patch("/api/work-orders/" + wo.getId() + "/status", Map.of("status", "done")));
        JsonNode closed = json(get(BASE + "/abnormalities/" + ab.getId() + "/rectification"));
        assertThat(closed.get("closedLoop").asBoolean()).isTrue();
        assertThat(closed.get("closedLoopAt").isNull()).isFalse();
        assertThat(closed.get("pendingReasons").size()).isZero();
        assertThat(closed.get("workOrderStatus").asText()).isEqualTo("done");

        // 闭环后不能再新增措施
        ResponseEntity<String> afterClosed = post(BASE + "/abnormalities/" + ab.getId() + "/actions",
                Map.of("category", "temporary", "title", "多余措施", "ownerName", "王巡检",
                        "actor", "质量主管"));
        assertThat(afterClosed.getStatusCode().value()).isEqualTo(409);

        // 事件流包含闭环事件
        JsonNode events = json(get(BASE + "/abnormalities/" + ab.getId() + "/events"));
        assertThat(toStringList(events.findValuesAsText("type"))).contains("closed_loop");
    }

    @Test
    void criticalAbnormality_requiresCauseAnalysisApproval() throws Exception {
        Equipment e = newEquipment("EQ-IT-2");
        InspectionTask t = newTask();
        WorkOrder wo = newWorkOrder(e.getId(), "open");
        InspectionAbnormality ab = newAbnormality(t, e, wo, "high");

        long a1 = createAction(ab.getId(), "temporary", "停机检查", "王巡检", null, List.of());
        json(post(BASE + "/abnormalities/actions/" + a1 + "/submit",
                Map.of("evidence", "检查记录", "actor", "王巡检")));
        json(post(BASE + "/abnormalities/actions/" + a1 + "/verify",
                Map.of("pass", true, "comment", "ok", "actor", "陈质量")));
        json(post(BASE + "/tasks/abnormality/recheck",
                Map.of("abnormalityId", ab.getId(), "result", "passed", "recheckBy", "陈质量")));
        json(patch("/api/work-orders/" + wo.getId() + "/status", Map.of("status", "done")));

        // 原因分析未获批 → 严重异常不得闭环
        JsonNode noCause = json(get(BASE + "/abnormalities/" + ab.getId() + "/rectification"));
        assertThat(noCause.get("closedLoop").asBoolean()).isFalse();
        assertThat(toStringList(noCause.get("pendingReasons"))).anyMatch(s -> s.contains("原因分析"));

        // 提交原因分析
        json(post(BASE + "/abnormalities/" + ab.getId() + "/cause-analysis",
                Map.of("causeAnalysis", "轴承磨损导致转子不平衡，振动超标", "actor", "赵工程师")));
        assertThat(get(BASE + "/abnormalities/" + ab.getId() + "/rectification").getBody())
                .contains("\"causeApproved\":null");

        // 审批人不能是提交人
        ResponseEntity<String> selfApprove = post(BASE + "/abnormalities/" + ab.getId() + "/cause-approval",
                Map.of("approved", true, "actor", "赵工程师"));
        assertThat(selfApprove.getStatusCode().value()).isEqualTo(422);

        // 驳回必须填写原因
        ResponseEntity<String> rejectNoReason = post(BASE + "/abnormalities/" + ab.getId() + "/cause-approval",
                Map.of("approved", false, "actor", "陈质量"));
        assertThat(rejectNoReason.getStatusCode().value()).isEqualTo(422);

        // 驳回后仍不闭环
        json(post(BASE + "/abnormalities/" + ab.getId() + "/cause-approval",
                Map.of("approved", false, "reason", "缺少频谱分析证据", "actor", "陈质量")));
        assertThat(json(get(BASE + "/abnormalities/" + ab.getId() + "/rectification")).get("closedLoop").asBoolean()).isFalse();

        // 重新提交并由质量主管批准 → 闭环
        json(post(BASE + "/abnormalities/" + ab.getId() + "/cause-analysis",
                Map.of("causeAnalysis", "轴承磨损导致不平衡，附频谱图与对中数据", "actor", "赵工程师")));
        json(post(BASE + "/abnormalities/" + ab.getId() + "/cause-approval",
                Map.of("approved", true, "actor", "陈质量")));
        assertThat(json(get(BASE + "/abnormalities/" + ab.getId() + "/rectification")).get("closedLoop").asBoolean()).isTrue();
    }

    @Test
    void riskAcceptance_neverClosesLoop_andShowsExpiry() throws Exception {
        Equipment e = newEquipment("EQ-IT-3");
        InspectionTask t = newTask();
        WorkOrder wo = newWorkOrder(e.getId(), "open");
        InspectionAbnormality ab = newAbnormality(t, e, wo, "medium");

        // 没有未完成长期措施时，不允许登记风险接受
        ResponseEntity<String> noLongTerm = post(BASE + "/abnormalities/" + ab.getId() + "/risk-acceptance",
                Map.of("reason", "备件采购周期长", "expiryDate", LocalDateTime.now().plusDays(30).toString(),
                        "actor", "陈质量"));
        assertThat(noLongTerm.getStatusCode().value()).isEqualTo(409);

        long lt = createAction(ab.getId(), "long_term", "泵基础灌浆重做", "赵工程师",
                LocalDateTime.now().plusDays(30), List.of());

        // 到期日必须是未来时间
        ResponseEntity<String> badExpiry = post(BASE + "/abnormalities/" + ab.getId() + "/risk-acceptance",
                Map.of("reason", "备件采购周期长", "expiryDate", LocalDateTime.now().minusDays(1).toString(),
                        "actor", "陈质量"));
        assertThat(badExpiry.getStatusCode().value()).isEqualTo(422);

        JsonNode ra = json(post(BASE + "/abnormalities/" + ab.getId() + "/risk-acceptance",
                Map.of("reason", "备件采购周期长，带风险运行并加密监测",
                        "expiryDate", LocalDateTime.now().plusDays(30).toString(),
                        "actor", "陈质量")));
        assertThat(ra.get("riskAccepted").asBoolean()).isTrue();
        assertThat(ra.get("closedLoop").asBoolean()).isFalse();
        assertThat(ra.get("riskExpiryDate").isNull()).isFalse();

        // 即便复检通过且工单完成，长期措施未完成 + 风险接受 ≠ 闭环
        json(post(BASE + "/tasks/abnormality/recheck",
                Map.of("abnormalityId", ab.getId(), "result", "passed", "recheckBy", "陈质量")));
        json(patch("/api/work-orders/" + wo.getId() + "/status", Map.of("status", "done")));
        JsonNode stillOpen = json(get(BASE + "/abnormalities/" + ab.getId() + "/rectification"));
        assertThat(stillOpen.get("closedLoop").asBoolean()).isFalse();
        assertThat(stillOpen.get("status").asText()).isEqualTo("risk_accepted");

        // 风险到期后视图明确提示
        InspectionAbnormality entity = abnormalityRepo.findById(ab.getId()).orElseThrow();
        entity.setRiskExpiryDate(LocalDateTime.now().minusHours(1));
        abnormalityRepo.save(entity);
        JsonNode expired = json(get(BASE + "/abnormalities/" + ab.getId() + "/rectification"));
        assertThat(expired.get("riskExpired").asBoolean()).isTrue();

        // 长期措施完成验证后才可闭环
        AbnormalityCorrectiveAction ltEntity = actionRepo.findById(lt).orElseThrow();
        ltEntity.setStatus("submitted");
        ltEntity.setCurrentRound(1);
        ltEntity.setSubmittedBy("赵工程师");
        ltEntity.setSubmittedAt(LocalDateTime.now());
        ltEntity.setCompletionEvidence("灌浆验收单");
        actionRepo.save(ltEntity);
        json(post(BASE + "/abnormalities/actions/" + lt + "/verify",
                Map.of("pass", true, "comment", "验收合格", "actor", "陈质量")));
        assertThat(json(get(BASE + "/abnormalities/" + ab.getId() + "/rectification")).get("closedLoop").asBoolean()).isTrue();
    }

    @Test
    void rejectionKeepsRound_andDuplicateCallbacksAreIdempotent() throws Exception {
        Equipment e = newEquipment("EQ-IT-4");
        InspectionTask t = newTask();
        InspectionAbnormality ab = newAbnormality(t, e, null, "low");

        long a1 = createAction(ab.getId(), "temporary", "紧固地脚螺栓", "王巡检", null, List.of());
        json(post(BASE + "/abnormalities/actions/" + a1 + "/submit",
                Map.of("evidence", "扭矩记录v1", "actor", "王巡检")));

        // 验证失败：退回 open，轮次保留为第 1 轮
        JsonNode rejected = json(post(BASE + "/abnormalities/actions/" + a1 + "/verify",
                Map.of("pass", false, "comment", "扭矩不足，重新紧固", "actor", "陈质量")));
        assertThat(rejected.get("status").asText()).isEqualTo("open");
        assertThat(rejected.get("currentRound").asInt()).isEqualTo(1);

        // 重新提交：轮次递增为 2，历史事件保留
        json(post(BASE + "/abnormalities/actions/" + a1 + "/submit",
                Map.of("evidence", "扭矩记录v2", "actor", "王巡检")));
        JsonNode a2 = json(post(BASE + "/abnormalities/actions/" + a1 + "/verify",
                Map.of("pass", true, "comment", "复测合格", "actor", "陈质量")));
        assertThat(a2.get("status").asText()).isEqualTo("verified");
        assertThat(a2.get("currentRound").asInt()).isEqualTo(2);

        JsonNode events = json(get(BASE + "/abnormalities/actions/" + a1 + "/events"));
        List<String> types = toStringList(events.findValuesAsText("type"));
        assertThat(types).containsExactly("created", "submitted", "rejected", "submitted", "verified");

        // 重复通过回调：幂等成功，不新增事件
        ResponseEntity<String> dup = post(BASE + "/abnormalities/actions/" + a1 + "/verify",
                Map.of("pass", true, "comment", "重复回调", "actor", "陈质量"));
        assertThat(dup.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode eventsAfter = json(get(BASE + "/abnormalities/actions/" + a1 + "/events"));
        assertThat(eventsAfter.findValuesAsText("type").stream().filter("verified"::equals).toList()).hasSize(1);

        // 已通过的措施不能再驳回
        ResponseEntity<String> rejectAfter = post(BASE + "/abnormalities/actions/" + a1 + "/verify",
                Map.of("pass", false, "comment", "迟到驳回", "actor", "陈质量"));
        assertThat(rejectAfter.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void workOrderCancelAndReopen_reopensLoopButKeepsRecheck() throws Exception {
        Equipment e = newEquipment("EQ-IT-5");
        InspectionTask t = newTask();
        WorkOrder wo = newWorkOrder(e.getId(), "open");
        InspectionAbnormality ab = newAbnormality(t, e, wo, "low");
        long a1 = createAction(ab.getId(), "temporary", "更换密封", "王巡检", null, List.of());
        json(post(BASE + "/abnormalities/actions/" + a1 + "/submit",
                Map.of("evidence", "维修单", "actor", "王巡检")));
        json(post(BASE + "/abnormalities/actions/" + a1 + "/verify",
                Map.of("pass", true, "comment", "ok", "actor", "陈质量")));
        json(post(BASE + "/tasks/abnormality/recheck",
                Map.of("abnormalityId", ab.getId(), "result", "passed", "recheckBy", "陈质量")));
        json(patch("/api/work-orders/" + wo.getId() + "/status", Map.of("status", "done")));
        assertThat(json(get(BASE + "/abnormalities/" + ab.getId() + "/rectification")).get("closedLoop").asBoolean()).isTrue();

        // 工单取消 → 异常重新打开，但复检记录保留
        json(patch("/api/work-orders/" + wo.getId() + "/status", Map.of("status", "cancelled")));
        JsonNode reopened = json(get(BASE + "/abnormalities/" + ab.getId() + "/rectification"));
        assertThat(reopened.get("closedLoop").asBoolean()).isFalse();
        assertThat(reopened.get("recheckResult").asText()).isEqualTo("passed");
        assertThat(reopened.get("recheckAt").isNull()).isFalse();

        JsonNode events = json(get(BASE + "/abnormalities/" + ab.getId() + "/events"));
        List<String> types = toStringList(events.findValuesAsText("type"));
        assertThat(types).contains("work_order_synced", "closed_loop_reopened");
        long syncCountBefore = types.stream().filter("work_order_synced"::equals).count();

        // 重复回调（再次 PATCH 相同状态）不重复记录同步事件
        json(patch("/api/work-orders/" + wo.getId() + "/status", Map.of("status", "cancelled")));
        JsonNode events2 = json(get(BASE + "/abnormalities/" + ab.getId() + "/events"));
        assertThat(events2.findValuesAsText("type").stream().filter("work_order_synced"::equals).toList())
                .hasSize((int) syncCountBefore);

        // 工单重新打开后再完成 → 重新闭环
        json(patch("/api/work-orders/" + wo.getId() + "/status", Map.of("status", "open")));
        assertThat(json(get(BASE + "/abnormalities/" + ab.getId() + "/rectification")).get("closedLoop").asBoolean()).isFalse();
        json(patch("/api/work-orders/" + wo.getId() + "/status", Map.of("status", "done")));
        assertThat(json(get(BASE + "/abnormalities/" + ab.getId() + "/rectification")).get("closedLoop").asBoolean()).isTrue();
    }

    @Test
    void partialMeasureCompletion_doesNotCloseLoop() throws Exception {
        Equipment e = newEquipment("EQ-IT-6");
        InspectionTask t = newTask();
        WorkOrder wo = newWorkOrder(e.getId(), "open");
        InspectionAbnormality ab = newAbnormality(t, e, wo, "medium");
        long a1 = createAction(ab.getId(), "temporary", "措施一", "王巡检", null, List.of());
        createAction(ab.getId(), "long_term", "措施二", "赵工程师", null, List.of());

        json(post(BASE + "/abnormalities/actions/" + a1 + "/submit",
                Map.of("evidence", "e1", "actor", "王巡检")));
        json(post(BASE + "/abnormalities/actions/" + a1 + "/verify",
                Map.of("pass", true, "comment", "ok", "actor", "陈质量")));
        json(post(BASE + "/tasks/abnormality/recheck",
                Map.of("abnormalityId", ab.getId(), "result", "passed", "recheckBy", "陈质量")));
        json(patch("/api/work-orders/" + wo.getId() + "/status", Map.of("status", "done")));

        JsonNode v = json(get(BASE + "/abnormalities/" + ab.getId() + "/rectification"));
        assertThat(v.get("closedLoop").asBoolean()).isFalse();
        assertThat(v.get("actions").size()).isEqualTo(2);
    }

    @Test
    void overviewAggregatesDependenciesOverdueAndTaskTrace() throws Exception {
        Equipment e = newEquipment("EQ-IT-7");
        InspectionTask t = newTask();
        WorkOrder wo = newWorkOrder(e.getId(), "open");
        InspectionAbnormality ab = newAbnormality(t, e, wo, "medium");
        long a1 = createAction(ab.getId(), "temporary", "前置措施", "王巡检",
                LocalDateTime.now().minusDays(1), List.of());
        createAction(ab.getId(), "long_term", "后续措施", "赵工程师",
                LocalDateTime.now().plusDays(5), List.of(a1));

        // 逾期过滤
        JsonNode overdue = json(get(BASE + "/abnormalities/rectification?overdueOnly=true"));
        assertThat(overdue.isArray()).isTrue();
        assertThat(overdue.size()).isGreaterThanOrEqualTo(1);
        boolean found = false;
        for (JsonNode node : overdue) {
            if (node.get("id").asLong() == ab.getId()) {
                found = true;
                JsonNode actions = node.get("actions");
                JsonNode pre = actions.get(1).get("prerequisites").get(0);
                assertThat(pre.get("id").asLong()).isEqualTo(a1);
                assertThat(pre.get("satisfied").asBoolean()).isFalse();
                assertThat(actions.get(0).get("overdue").asBoolean()).isTrue();
            }
        }
        assertThat(found).isTrue();

        // 巡检员从原任务追到当前整改进展
        JsonNode byTask = json(get(BASE + "/tasks/" + t.getId() + "/rectification"));
        assertThat(byTask.isArray()).isTrue();
        assertThat(byTask.get(0).get("taskCode").asText()).isEqualTo(t.getCode());
        assertThat(byTask.get(0).get("actions").size()).isEqualTo(2);
    }

    private List<String> toStringList(JsonNode arr) {
        return om.<List<String>>convertValue(arr,
                om.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private List<String> toStringList(List<String> l) {
        return l;
    }

    /** 极简 JSON 路径读取（仅 /id） */
    private static class JsonPath {
        static JsonNode read(ResponseEntity<String> r, String path) {
            try {
                JsonNode root = new ObjectMapper().readTree(r.getBody());
                JsonNode cur = root;
                for (String p : path.replaceFirst("^/", "").split("/")) {
                    cur = cur.get(p);
                }
                return cur;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}
