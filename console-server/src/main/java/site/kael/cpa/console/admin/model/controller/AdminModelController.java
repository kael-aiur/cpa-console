package site.kael.cpa.console.admin.model.controller;
import org.springframework.web.bind.annotation.*;
import site.kael.cpa.console.admin.model.dto.*;
import site.kael.cpa.console.admin.model.service.AdminModelService;
@RestController @RequestMapping("/admin/models")
public class AdminModelController {
 private final AdminModelService service;
 public AdminModelController(AdminModelService service){this.service=service;}
 @GetMapping public AdminAvailableModelListResponse models(){return service.models();}
 @PatchMapping("/{id}") public AdminAvailableModelResponse update(@PathVariable("id") long id,@RequestBody AdminAvailableModelUpdateRequest request){if(request==null)throw new IllegalArgumentException("Request body is required");return service.update(id,request.litellm_model_id());}
 @GetMapping("/metadata") public AdminLiteLlmMetadataListResponse metadata(){return service.metadata();}
 @GetMapping("/metadata/sync-config") public AdminLiteLlmSyncConfigResponse config(){return service.config();}
 @PutMapping("/metadata/sync-config") public AdminLiteLlmSyncConfigResponse updateConfig(@RequestBody AdminLiteLlmSyncConfigRequest request){return service.updateConfig(request);}
 @PostMapping("/metadata/sync") public java.util.Map<String,Integer> sync(){return java.util.Map.of("count",service.syncMetadata());}
}
