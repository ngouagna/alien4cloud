package alien4cloud.deployment.matching.services.nodes;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.alien4cloud.tosca.catalog.index.IToscaTypeSearchService;
import org.alien4cloud.tosca.model.CSARDependency;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.definitions.CapabilityDefinition;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.ServiceNodeTemplate;
import org.alien4cloud.tosca.model.types.CapabilityType;
import org.alien4cloud.tosca.model.types.NodeType;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import alien4cloud.component.ICSARRepositorySearchService;
import alien4cloud.deployment.matching.plugins.INodeMatcherPlugin;
import alien4cloud.exception.InvalidArgumentException;
import alien4cloud.model.deployment.matching.MatchingConfiguration;
import alien4cloud.model.orchestrators.locations.Location;
import alien4cloud.model.orchestrators.locations.LocationResourceTemplate;
import alien4cloud.model.orchestrators.locations.LocationResources;
import alien4cloud.model.service.ServiceResource;
import alien4cloud.orchestrators.locations.services.ILocationResourceService;
import alien4cloud.orchestrators.locations.services.LocationMatchingConfigurationService;
import alien4cloud.orchestrators.locations.services.LocationSecurityService;
import alien4cloud.orchestrators.locations.services.LocationService;
import alien4cloud.service.ServiceResourceService;

/**
 * Node matcher service will filter location resources for all substitutable nodes of the topology. It will return only location resources that can substitute a
 * node.
 */
@Service
public class NodeMatcherService {
    @Inject
    private DefaultNodeMatcher defaultNodeMatcher;
    @Inject
    private LocationService locationService;
    @Inject
    private ServiceResourceService serviceResourceService;
    @Inject
    @Lazy(true)
    private ILocationResourceService locationResourceService;
    @Inject
    private LocationMatchingConfigurationService locationMatchingConfigurationService;
    @Inject
    private LocationSecurityService locationSecurityService;
    @Inject
    private IToscaTypeSearchService toscaTypeSearchService;
    @Inject
    private ICSARRepositorySearchService csarRepoSearchService;

    private INodeMatcherPlugin getNodeMatcherPlugin() {
        // TODO manage plugins
        return defaultNodeMatcher;
    }

    public Map<String, List<LocationResourceTemplate>> match(Map<String, NodeType> nodesTypes, Map<String, NodeTemplate> nodesToMatch, String locationId) {
        return match(nodesTypes, nodesToMatch, locationId, null);
    }

    public Map<String, List<LocationResourceTemplate>> match(Map<String, NodeType> nodesTypes, Map<String, NodeTemplate> nodesToMatch, String locationId,
            String environmentId) {
        Map<String, List<LocationResourceTemplate>> matchingResult = Maps.newHashMap();
        Location location = locationService.getOrFail(locationId);
        LocationResources locationResources = locationResourceService.getLocationResources(location);

        // Authorization filtering of services resources
        filterOnAuthorization(locationResources, environmentId);

        List<ServiceResource> services = serviceResourceService.searchByLocation(locationId);
        filterOnAuthorization(services, locationId);
        populateLocationResourcesWithServiceResource(locationResources, services, locationId);

        Map<String, MatchingConfiguration> matchingConfigurations = locationMatchingConfigurationService.getMatchingConfiguration(location);
        Set<String> typesManagedByLocation = Sets.newHashSet();
        for (NodeType nodeType : locationResources.getNodeTypes().values()) {
            typesManagedByLocation.add(nodeType.getElementId());
            typesManagedByLocation.addAll(nodeType.getDerivedFrom());
        }
        INodeMatcherPlugin nodeMatcherPlugin = getNodeMatcherPlugin();
        for (Map.Entry<String, NodeTemplate> nodeTemplateEntry : nodesToMatch.entrySet()) {
            String nodeTemplateId = nodeTemplateEntry.getKey();
            NodeTemplate nodeTemplate = nodeTemplateEntry.getValue();
            if (typesManagedByLocation.contains(nodeTemplate.getType())) {
                NodeType nodeTemplateType = nodesTypes.get(nodeTemplate.getType());
                if (nodeTemplateType == null) {
                    throw new InvalidArgumentException("The given node types map must contain the type of the node template");
                }
                matchingResult.put(nodeTemplateId, nodeMatcherPlugin.matchNode(nodeTemplate, nodeTemplateType, locationResources, matchingConfigurations));
            }
        }
        return matchingResult;
    }

    private void filterOnAuthorization(LocationResources locationResources, String environmentId) {
        locationResources.getNodeTemplates()
                .removeIf(locationResourceTemplate -> !locationSecurityService.isAuthorised(locationResourceTemplate, environmentId));
    }

    private void filterOnAuthorization(List<ServiceResource> serviceResource, String environmentId) {
        serviceResource
                .removeIf(locationResourceTemplate -> !locationSecurityService.isAuthorised(locationResourceTemplate, environmentId));
    }

    /**
     * Populate this {@link LocationResources} using these {@link ServiceResource}s in order to make them available as {@link LocationResourceTemplate} for
     * matching purpose.
     *
     * TODO: Improve this ugly code to put ServiceResource in LocationResourceTemplates.
     */
    private void populateLocationResourcesWithServiceResource(LocationResources locationResources, List<ServiceResource> services, String locationId) {
        for (ServiceResource serviceResource : services) {
            LocationResourceTemplate lrt = new LocationResourceTemplate();
            lrt.setService(true);
            lrt.setEnabled(true);
            // for a service we also want to display the version, so just add it to the name
            lrt.setName(serviceResource.getName() + ":" + serviceResource.getVersion());
            lrt.setId(serviceResource.getId());

            ServiceNodeTemplate serviceNodeTemplate = new ServiceNodeTemplate(serviceResource.getNodeInstance());
            lrt.setTemplate(serviceNodeTemplate);
            lrt.setLocationId(locationId);

            String serviceTypeName = serviceResource.getNodeInstance().getNodeTemplate().getType();
            List<String> types = Lists.newArrayList(serviceTypeName);
            lrt.setTypes(types);
            NodeType serviceType = toscaTypeSearchService.findOrFail(NodeType.class, serviceTypeName, serviceResource.getNodeInstance().getTypeVersion());
            types.addAll(serviceType.getDerivedFrom());

            locationResources.getNodeTypes().put(serviceTypeName, serviceType);

            Csar csar = toscaTypeSearchService.getArchive(serviceType.getArchiveName(), serviceType.getArchiveVersion());
            Set<CSARDependency> dependencies = Sets.newHashSet();
            if (csar.getDependencies() != null) {
                dependencies.addAll(csar.getDependencies());
            }
            dependencies.add(new CSARDependency(csar.getName(), csar.getVersion()));
            if (serviceType.getCapabilities() != null && !serviceType.getCapabilities().isEmpty()) {
                for (CapabilityDefinition capabilityDefinition : serviceType.getCapabilities()) {
                    locationResources.getCapabilityTypes().put(capabilityDefinition.getType(),
                            csarRepoSearchService.getRequiredElementInDependencies(CapabilityType.class, capabilityDefinition.getType(), dependencies));
                }
            }

            locationResources.getNodeTemplates().add(lrt);
        }

    }
}
