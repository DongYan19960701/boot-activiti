package com.qxf.controller;

import com.qxf.mapper.LeaveMapper;
import com.qxf.pojo.Leave;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.bpmn.model.FlowNode;
import org.activiti.bpmn.model.SequenceFlow;
import org.activiti.engine.*;
import org.activiti.engine.history.HistoricActivityInstance;
import org.activiti.engine.history.HistoricProcessInstance;
import org.activiti.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Task;
import org.activiti.image.ProcessDiagramGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * @ClassName
 * @Description TODO
 * @Author qiuxinfa
 * @Date 2020/7/5 22:43
 **/
@RestController
public class LeaveController {
    @Autowired
    private LeaveMapper leaveMapper;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private ProcessEngineConfiguration processEngineConfiguration;

    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    // 启动请假流程
    @GetMapping("/start")
    public String startLeaveProcess(){
        // 模拟系统用户
        String userId = "sam";
        System.out.println(userId + " ，开始申请请假");
        Leave leave = new Leave();
        leave.setId(UUID.randomUUID().toString().replace("-",""));
        leave.setUserId(userId);
        leave.setStartDate(new Date());
        leave.setEndDate(new Date());
        leave.setReason(userId+"，在 "+dateFormat.format(new Date())+" 请假");
        int cnt = leaveMapper.addLeave(leave);
        if (cnt <= 0){
            return "请假申请失败，请确认填写的信息是否正确";
        }
        // 设置认证用户
        identityService.setAuthenticatedUserId(userId);
        // 启动流程
        Map<String,Object> map = new HashMap<>(1);
        // 设置流程启动人
        map.put("applyUserId",userId);
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("leaveProcess", leave.getId(), map);
        System.out.println("请假流程启动成功，流程实例id为 "+processInstance.getId());
        // 回填 流程实例id
        leaveMapper.updateLeave(leave.getId(),processInstance.getId());

        return "请假流程启动成功";
    }

    // 是否请假
    @GetMapping("/apply")
    public String apply(String isApply){
        String userId = "sam";
        Task task = taskService.createTaskQuery().taskAssignee(userId).singleResult();
        if (task == null){
            return "请先启动流程，或者请假已经申请";
        }

        Map<String,Object> map = new HashMap<>(1);
        map.put("isApply",isApply);
        taskService.complete(task.getId(),map);

        String msg = "已撤销请假";
        if ("true".equals(isApply)){
            msg = "已提交请假";
        }
        System.out.println(msg);

        return msg;
    }

    // 领导审批
    @GetMapping("/leaderApprove")
    public String leaderApprove(String leaderAudit){
        String userId = "qiuxinfa";
        Task task = taskService.createTaskQuery().taskAssignee(userId).singleResult();
        if (task == null){
            return "没有待审批任务";
        }
        Map<String,Object> map = new HashMap<>(1);
        map.put("leaderAudit",leaderAudit);
        taskService.complete(task.getId(),map);
        String msg = "不同意请假";
        if ("true".equals(leaderAudit)){
            msg = "同意请假";
        }
        System.out.println("已完成审批，结果为： "+msg);
        return "已完成审批，结果为： "+msg;
    }

    // 我发起的请假流程
    @GetMapping("/myLeave")
    public List<Leave> getMyLeave(){
        String userId = "sam";
        // 用户参与的流程
        List<HistoricProcessInstance> processInstanceList = historyService.createHistoricProcessInstanceQuery()
                .processDefinitionKey("leaveProcess").startedBy(userId).list();
        List<Leave> leaveList = new ArrayList<>();

        if (processInstanceList != null && processInstanceList.size() > 0){
            for (HistoricProcessInstance instance : processInstanceList){
                Leave leave = leaveMapper.getLeaveById(instance.getBusinessKey());
                leaveList.add(leave);
            }
        }

        return leaveList;
    }

    // 我的请假记录
    @GetMapping("/record")
    public List<Leave> getMyLeaveRecord(){
        String userId = "sam";
        List<HistoricProcessInstance> processInstanceList = historyService.createHistoricProcessInstanceQuery()
                .processDefinitionKey("leaveProcess").startedBy(userId).finished().list();
        List<Leave> leaveList = new ArrayList<>();

        if (processInstanceList != null && processInstanceList.size() > 0){
            for (HistoricProcessInstance instance : processInstanceList){
                Leave leave = leaveMapper.getLeaveById(instance.getBusinessKey());
                leaveList.add(leave);
            }
        }

        return leaveList;
    }


    /**
     * 根据流程实例Id,获取实时流程图片
     *
     * @param processInstanceId
     * @return
     */
    @GetMapping("/trace")
    public void getFlowImgByInstanceId(String processInstanceId, HttpServletResponse response) {
        try {
            if (StringUtils.isEmpty(processInstanceId)) {
                System.err.println("processInstanceId is null");
                return;
            }
            // 获取历史流程实例
            HistoricProcessInstance historicProcessInstance = historyService.createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
            // 获取流程中已经执行的节点，按照执行先后顺序排序
            List<HistoricActivityInstance> historicActivityInstances = historyService.createHistoricActivityInstanceQuery().processInstanceId(processInstanceId)
                    .orderByHistoricActivityInstanceId().asc().list();
            // 高亮已经执行流程节点ID集合
            List<String> highLightedActivitiIds = new ArrayList<>();
            for (HistoricActivityInstance historicActivityInstance : historicActivityInstances) {
                highLightedActivitiIds.add(historicActivityInstance.getActivityId());
            }

            List<HistoricProcessInstance> historicFinishedProcessInstances = historyService.createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).finished()
                    .list();
            ProcessDiagramGenerator processDiagramGenerator = null;
            processDiagramGenerator = processEngineConfiguration.getProcessDiagramGenerator();
            // 如果还没完成，流程图高亮颜色为绿色，如果已经完成为红色
//            if (!CollectionUtils.isEmpty(historicFinishedProcessInstances)) {
//                // 如果不为空，说明已经完成
//                processDiagramGenerator = processEngineConfiguration.getProcessDiagramGenerator();
//            } else {
//                processDiagramGenerator = new CustomProcessDiagramGenerator();
//            }

            BpmnModel bpmnModel = repositoryService.getBpmnModel(historicProcessInstance.getProcessDefinitionId());
            // 高亮流程已发生流转的线id集合
            List<String> highLightedFlowIds = this.getHighLightedFlows(bpmnModel, historicActivityInstances);

            // 使用默认配置获得流程图表生成器，并生成追踪图片字符流
            InputStream imageStream = processDiagramGenerator.generateDiagram(bpmnModel, "png", highLightedActivitiIds, highLightedFlowIds, "宋体", "微软雅黑", "黑体", null, 2.0);

            // 输出图片内容
            byte[] b = new byte[1024];
            int len;

            ServletOutputStream outputStream = response.getOutputStream();

            while ((len = imageStream.read(b, 0, 1024)) != -1) {
                outputStream.write(b, 0, len);
            }
        } catch (Exception e) {
            System.err.println("processInstanceId" + processInstanceId + "生成流程图失败，原因：" + e.getMessage());
        }

    }

    /**
     * 获取已经流转的线
     *
     * @param bpmnModel
     * @param historicActivityInstances
     * @return
     */
    private List<String> getHighLightedFlows(BpmnModel bpmnModel, List<HistoricActivityInstance> historicActivityInstances) {
        // 高亮流程已发生流转的线id集合
        List<String> highLightedFlowIds = new ArrayList<>();
        // 全部活动节点
        List<FlowNode> historicActivityNodes = new ArrayList<>();
        // 已完成的历史活动节点
        List<HistoricActivityInstance> finishedActivityInstances = new ArrayList<>();

        for (HistoricActivityInstance historicActivityInstance : historicActivityInstances) {
            FlowNode flowNode = (FlowNode) bpmnModel.getMainProcess().getFlowElement(historicActivityInstance.getActivityId(), true);
            historicActivityNodes.add(flowNode);
            if (historicActivityInstance.getEndTime() != null) {
                finishedActivityInstances.add(historicActivityInstance);
            }
        }

        FlowNode currentFlowNode = null;
        FlowNode targetFlowNode = null;
        // 遍历已完成的活动实例，从每个实例的outgoingFlows中找到已执行的
        for (HistoricActivityInstance currentActivityInstance : finishedActivityInstances) {
            // 获得当前活动对应的节点信息及outgoingFlows信息
            currentFlowNode = (FlowNode) bpmnModel.getMainProcess().getFlowElement(currentActivityInstance.getActivityId(), true);
            List<SequenceFlow> sequenceFlows = currentFlowNode.getOutgoingFlows();

            /**
             * 遍历outgoingFlows并找到已已流转的 满足如下条件认为已已流转： 1.当前节点是并行网关或兼容网关，则通过outgoingFlows能够在历史活动中找到的全部节点均为已流转 2.当前节点是以上两种类型之外的，通过outgoingFlows查找到的时间最早的流转节点视为有效流转
             */
            if ("parallelGateway".equals(currentActivityInstance.getActivityType()) || "inclusiveGateway".equals(currentActivityInstance.getActivityType())) {
                // 遍历历史活动节点，找到匹配流程目标节点的
                for (SequenceFlow sequenceFlow : sequenceFlows) {
                    targetFlowNode = (FlowNode) bpmnModel.getMainProcess().getFlowElement(sequenceFlow.getTargetRef(), true);
                    if (historicActivityNodes.contains(targetFlowNode)) {
                        highLightedFlowIds.add(targetFlowNode.getId());
                    }
                }
            } else {
                List<Map<String, Object>> tempMapList = new ArrayList<>();
                for (SequenceFlow sequenceFlow : sequenceFlows) {
                    for (HistoricActivityInstance historicActivityInstance : historicActivityInstances) {
                        if (historicActivityInstance.getActivityId().equals(sequenceFlow.getTargetRef())) {
                            Map<String, Object> map = new HashMap<>();
                            map.put("highLightedFlowId", sequenceFlow.getId());
                            map.put("highLightedFlowStartTime", historicActivityInstance.getStartTime().getTime());
                            tempMapList.add(map);
                        }
                    }
                }

                if (!CollectionUtils.isEmpty(tempMapList)) {
                    // 遍历匹配的集合，取得开始时间最早的一个
                    long earliestStamp = 0L;
                    String highLightedFlowId = null;
                    for (Map<String, Object> map : tempMapList) {
                        long highLightedFlowStartTime = Long.valueOf(map.get("highLightedFlowStartTime").toString());
                        if (earliestStamp == 0 || earliestStamp >= highLightedFlowStartTime) {
                            highLightedFlowId = map.get("highLightedFlowId").toString();
                            earliestStamp = highLightedFlowStartTime;
                        }
                    }

                    highLightedFlowIds.add(highLightedFlowId);
                }

            }

        }
        return highLightedFlowIds;
    }

}
