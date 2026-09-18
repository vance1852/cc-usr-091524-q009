package com.admin.equipment.rectification;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.inspection.InspectionAbnormality;
import com.admin.equipment.repo.AppUserRepository;
import com.admin.equipment.security.JwtUtil;
import com.admin.equipment.security.PasswordUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 端到端 API 集成测试：鉴权 → 措施/验证/审批/复检/工单状态联动 → closedLoop，
 * 以及巡检员任务进展、质量主管聚合视图。
 */
@AutoConfigureMockMvc
class RectificationApiIntegrationTest extends AbstractRectificationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private AppUserRepository userRepo;
    @Autowired
    private ObjectMapper objectMapper;

    private String token;

    @BeforeEach
    void setUp() {
        AppUser user = userRepo.findByUsername("apitester").orElseGet(() -> {
            AppUser u = new AppUser();
            u.setUsername("apitester");
            u.setPasswordHash(PasswordUtil.hash("x"));
            u.setDisplayName("接口测试员");
            return userRepo.save(u);
        });
        token = jwtUtil.createToken(user.getId(), user.getUsername());
    }

    private String auth() {
        return "Bearer " + token;
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void httpHappyPath_closesOnlyAfterEveryGate() throws Exception {
        InspectionAbnormality ab = newAbnormalityWithWorkOrder("urgent");
        long aid = ab.getId();
        long woId = ab.getWorkOrderId();

        // 无凭证 401
        mockMvc.perform(get("/api/inspection/abnormalities/" + aid + "/rectification"))
                .andExpect(status().isUnauthorized());

        // 建两项措施（带依赖、逾期/未逾期）
        String m1Body = """
                {"category":"temporary","title":"临时加固地脚","owner":"张维保",
                 "dueDate":"%s","predecessorIds":[]}
                """.formatted(LocalDate.now().minusDays(1));
        MvcResult r1 = mockMvc.perform(post("/api/inspection/abnormalities/" + aid + "/measures")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON).content(m1Body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(1))
                .andReturn();
        long m1 = json(r1).get("id").asLong();

        String m2Body = """
                {"category":"long_term","title":"更换联轴器对中","owner":"赵工程师",
                 "dueDate":"%s","predecessorIds":[%d]}
                """.formatted(LocalDate.now().plusDays(10), m1);
        MvcResult r2 = mockMvc.perform(post("/api/inspection/abnormalities/" + aid + "/measures")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON).content(m2Body))
                .andExpect(status().isCreated())
                .andReturn();
        long m2 = json(r2).get("id").asLong();

        // 聚合详情：依赖、逾期标记
        mockMvc.perform(get("/api/inspection/abnormalities/" + aid + "/rectification")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measures[0].overdue").value(true))
                .andExpect(jsonPath("$.measures[1].predecessorSeqs[0]").value(1))
                .andExpect(jsonPath("$.closedLoop").value(false))
                .andExpect(jsonPath("$.closureBlockers[0]").exists());

        // m2 在 m1 验证前提交 -> 409
        mockMvc.perform(post("/api/inspection/abnormalities/" + aid + "/measures/" + m2 + "/submit")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submittedBy":"赵工程师","evidence":"对中报告"}"""))
                .andExpect(status().isConflict());

        // m1 提交 -> 自验 422 -> 验证通过
        mockMvc.perform(post("/api/inspection/abnormalities/" + aid + "/measures/" + m1 + "/submit")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submittedBy":"张维保","evidence":"力矩记录+照片"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verifyRound").value(1));
        mockMvc.perform(post("/api/inspection/abnormalities/" + aid + "/measures/" + m1 + "/verify")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verifier":"张维保","passed":true}"""))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/inspection/abnormalities/" + aid + "/measures/" + m1 + "/verify")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verifier":"李质检","passed":true,"comment":"合格"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("verified"))
                .andExpect(jsonPath("$.verifiedBy").value("李质检"));

        // m2 提交、验证
        mockMvc.perform(post("/api/inspection/abnormalities/" + aid + "/measures/" + m2 + "/submit")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submittedBy":"赵工程师","evidence":"对中报告+频谱"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/inspection/abnormalities/" + aid + "/measures/" + m2 + "/verify")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verifier":"李质检","passed":true,"comment":"振动达标"}"""))
                .andExpect(status().isOk());

        // 严重异常：复检通过 + 工单完成，但原因分析未批 -> 不闭环
        mockMvc.perform(post("/api/inspection/tasks/abnormality/recheck")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"abnormalityId":%d,"result":"passed","recheckBy":"王巡检"}""".formatted(aid)))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/work-orders/" + woId + "/status")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"done"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/inspection/abnormalities/" + aid + "/rectification")
                        .header("Authorization", auth()))
                .andExpect(jsonPath("$.closedLoop").value(false));

        // 原因分析提交并获批
        mockMvc.perform(post("/api/inspection/abnormalities/" + aid + "/cause-analysis")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analysis":"联轴器不对中","submitter":"赵工程师"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/inspection/abnormalities/" + aid + "/cause-analysis/approval")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approver":"质量主管","approved":true,"comment":"同意"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/inspection/abnormalities/" + aid + "/rectification")
                        .header("Authorization", auth()))
                .andExpect(jsonPath("$.closedLoop").value(true))
                .andExpect(jsonPath("$.abnormality.status").value("resolved"));

        // 巡检员从原任务追踪进展
        mockMvc.perform(get("/api/inspection/tasks/" + ab.getTaskId() + "/rectification-progress")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].closedLoop").value(true))
                .andExpect(jsonPath("$[0].verifiedCount").value(2));

        // 工单重开 -> 闭环解除，复检记录仍在
        mockMvc.perform(patch("/api/work-orders/" + woId + "/status")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"open"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/inspection/abnormalities/" + aid + "/rectification")
                        .header("Authorization", auth()))
                .andExpect(jsonPath("$.closedLoop").value(false))
                .andExpect(jsonPath("$.abnormality.recheckResult").value("passed"));
    }

    @Test
    void riskAcceptanceShowsInSupervisorOverviewWithExpiry() throws Exception {
        InspectionAbnormality ab = newAbnormalityWithWorkOrder("high");
        long aid = ab.getId();

        String body = """
                {"category":"long_term","title":"长周期改造","owner":"赵工程师",
                 "dueDate":"%s","predecessorIds":[]}
                """.formatted(LocalDate.now().plusDays(30));
        MvcResult r = mockMvc.perform(post("/api/inspection/abnormalities/" + aid + "/measures")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        long measureId = json(r).get("id").asLong();
        // 逾期的临时措施
        String overdueBody = """
                {"category":"temporary","title":"监护运行","owner":"张维保",
                 "dueDate":"%s","predecessorIds":[]}
                """.formatted(LocalDate.now().minusDays(3));
        mockMvc.perform(post("/api/inspection/abnormalities/" + aid + "/measures")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON).content(overdueBody))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/inspection/abnormalities/" + aid + "/risk-acceptance")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"备件周期内降负荷","acceptedBy":"质量主管",
                                 "expireDate":"%s"}""".formatted(LocalDate.now().plusDays(7))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outstandingMeasureIds").value(String.valueOf(measureId)));

        // 风险接受后仍未闭环
        mockMvc.perform(get("/api/inspection/abnormalities/" + aid + "/rectification")
                        .header("Authorization", auth()))
                .andExpect(jsonPath("$.closedLoop").value(false));

        // 主管聚合视图：逾期项 + 风险接受到期日
        mockMvc.perform(get("/api/inspection/supervision/overview")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdueMeasures[0].owner").value("张维保"))
                .andExpect(jsonPath("$.overdueMeasures[0].daysOverdue").value(3))
                .andExpect(jsonPath("$.riskAcceptances[0].acceptedBy").value("质量主管"))
                .andExpect(jsonPath("$.riskAcceptances[0].expireDate").exists());
    }

    @Test
    void validationErrors_return422() throws Exception {
        InspectionAbnormality ab = newAbnormalityWithWorkOrder("medium");
        long aid = ab.getId();

        mockMvc.perform(post("/api/inspection/abnormalities/" + aid + "/measures")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"bad","title":""}"""))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get("/api/inspection/abnormalities/999999/rectification")
                        .header("Authorization", auth()))
                .andExpect(status().isNotFound());
    }
}
