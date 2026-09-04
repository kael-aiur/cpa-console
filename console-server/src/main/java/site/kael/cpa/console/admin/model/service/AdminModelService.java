package site.kael.cpa.console.admin.model.service;
import org.springframework.stereotype.Service;
import site.kael.cpa.console.admin.model.dto.*;
import site.kael.cpa.console.core.model.manager.AvailableModelManager;
import site.kael.cpa.console.core.model.manager.LiteLlmModelManager;
@Service
public class AdminModelService {
 private final AvailableModelManager available; private final LiteLlmModelManager metadata;
 public AdminModelService(AvailableModelManager available, LiteLlmModelManager metadata) { this.available=available; this.metadata=metadata; }
 public AdminAvailableModelListResponse models() { var list=available.list(); return new AdminAvailableModelListResponse(list.stream().map(AdminAvailableModelResponse::from).toList(),list.size()); }
 public AdminAvailableModelResponse update(long id,String modelId) { return AdminAvailableModelResponse.from(available.updateLiteLlmModelId(id,modelId)); }
 public AdminLiteLlmMetadataListResponse metadata() { var list=metadata.list(); return new AdminLiteLlmMetadataListResponse(list.stream().map(AdminLiteLlmMetadataResponse::from).toList(),list.size()); }
 public AdminLiteLlmSyncConfigResponse config() { return AdminLiteLlmSyncConfigResponse.from(metadata.config()); }
 public AdminLiteLlmSyncConfigResponse updateConfig(AdminLiteLlmSyncConfigRequest request) { if(request==null) throw new IllegalArgumentException("Request body is required"); return AdminLiteLlmSyncConfigResponse.from(metadata.updateConfig(request.url(),request.proxy_enabled(),request.proxy_host(),request.proxy_port())); }
 public int syncMetadata() { return metadata.synchronize(); }
}
