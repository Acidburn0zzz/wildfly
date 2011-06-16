/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.domain.controller.operations.coordination;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jboss.as.controller.NewOperationContext;
import org.jboss.as.controller.NewStepHandler;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DOMAIN_FAILURE_DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILURE_DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST_FAILURE_DESCRIPTIONS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.IGNORED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESPONSE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ROLLED_BACK;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_GROUPS;
import org.jboss.as.domain.controller.ServerIdentity;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * Assembles the overall result for a domain operation from individual host and server results.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class DomainResultHandler implements NewStepHandler {

    private final DomainOperationContext domainOperationContext;

    public DomainResultHandler(DomainOperationContext domainOperationContext) {
        this.domainOperationContext = domainOperationContext;
    }

    @Override
    public void execute(NewOperationContext context, ModelNode operation) throws OperationFailedException {

        // Wipe out any result that may have accumulated from previous handlers
        context.getResult().set(new ModelNode());

        final boolean isDomain = isDomainOperation(operation);
        boolean shouldContinue = !collectDomainFailure(context, isDomain);
        shouldContinue = shouldContinue && !collectHostFailures(context, isDomain);
        if(shouldContinue){
            if (domainOperationContext.getServerResults().size() == 0) {
                context.getResult().set(getSingleHostResult());
            } else {
                populateServerGroupResults(context, context.getResult());
            }
        }
        context.completeStep();
    }

    private boolean collectDomainFailure(NewOperationContext context, final boolean isDomain) {
        final ModelNode coordinator = domainOperationContext.getCoordinatorResult();
        ModelNode domainFailure = null;
        if (isDomain &&  coordinator != null && coordinator.hasDefined(OUTCOME) && FAILED.equals(coordinator.get(OUTCOME).asString())) {
            domainFailure = coordinator.hasDefined(FAILURE_DESCRIPTION) ? coordinator.get(FAILURE_DESCRIPTION) : new ModelNode().set("Unexplained failure");
        }
        if (domainFailure != null) {
            context.getFailureDescription().get(DOMAIN_FAILURE_DESCRIPTION).set(domainFailure);
            return true;
        }
        return false;
    }

    private boolean collectHostFailures(final NewOperationContext context, final boolean isDomain) {
        ModelNode hostFailureResults = null;
        for (Map.Entry<String, ModelNode> entry : domainOperationContext.getHostControllerResults().entrySet()) {
            ModelNode hostResult = entry.getValue();
            if (hostResult.hasDefined(OUTCOME) && FAILED.equals(hostResult.get(OUTCOME).asString())) {
                if (hostFailureResults == null) {
                    hostFailureResults = new ModelNode();
                }
                final ModelNode desc = hostResult.hasDefined(FAILURE_DESCRIPTION) ? hostResult.get(FAILURE_DESCRIPTION) : new ModelNode().set("Unexplained failure");
                hostFailureResults.add(entry.getKey(), desc);
            }
        }

        final ModelNode coordinator = domainOperationContext.getCoordinatorResult();
        if (!isDomain && coordinator != null && coordinator.hasDefined(OUTCOME) && FAILED.equals(coordinator.get(OUTCOME).asString())) {
            if (hostFailureResults == null) {
                hostFailureResults = new ModelNode();
            }
            final ModelNode desc = coordinator.hasDefined(FAILURE_DESCRIPTION) ? coordinator.get(FAILURE_DESCRIPTION) : new ModelNode().set("Unexplained failure");
            hostFailureResults.add(domainOperationContext.getLocalHostInfo().getLocalHostName(), desc);
        }

        if (hostFailureResults != null) {
            context.getFailureDescription().get(HOST_FAILURE_DESCRIPTIONS).set(hostFailureResults);
            return true;
        }
        return false;
    }

    private ModelNode getSingleHostResult() {
        ModelNode singleHost = domainOperationContext.getCoordinatorResult();
        if (singleHost != null
                && (!singleHost.hasDefined(RESULT)
                    || (ModelType.STRING == singleHost.get(RESULT).getType()
                        && IGNORED.equals(singleHost.get(RESULT).asString())))) {
            singleHost = null;
        }
        if (singleHost == null) {
            for (ModelNode node : domainOperationContext.getHostControllerResults().values()) {
                if (node.hasDefined(RESULT) && !IGNORED.equals(node.get(RESULT).asString())) {
                    singleHost = node;
                    break;
                }
            }
        }

        return singleHost == null ? new ModelNode() : singleHost.get(RESULT);
    }

    private void populateServerGroupResults(final NewOperationContext context, final ModelNode result) {
        final Set<String> groupNames = new TreeSet<String>();
        final Map<String, Set<HostServer>> groupToServerMap = new HashMap<String, Set<HostServer>>();
        for (Map.Entry<ServerIdentity, ModelNode> entry : domainOperationContext.getServerResults().entrySet()) {
            final String serverGroup = entry.getKey().getServerGroupName();
            groupNames.add(serverGroup);
            final String hostName = entry.getKey().getHostName();
            final String serverName = entry.getKey().getHostName();
            if (!groupToServerMap.containsKey(serverGroup)) {
                groupToServerMap.put(serverGroup, new TreeSet<HostServer>());
            }
            groupToServerMap.get(serverGroup).add(new HostServer(hostName, serverName, entry.getValue()));
        }

        boolean serverGroupSuccess = false;
        for (String groupName : groupNames) {
            final ModelNode groupNode = new ModelNode();
            if (domainOperationContext.isServerGroupRollback(groupName)) {
                groupNode.get(ROLLED_BACK).set(true);
            } else {
                serverGroupSuccess = true;
            }
            for (HostServer hostServer : groupToServerMap.get(groupName)) {
                final ModelNode serverNode = new ModelNode();
                serverNode.get(HOST).set(hostServer.hostName);
                serverNode.get(RESPONSE).set(hostServer.result);
                groupNode.add(hostServer.serverName, serverNode);
            }
            result.get(SERVER_GROUPS).add(groupName, groupNode);
        }
        if(!serverGroupSuccess) {
            context.getFailureDescription().set("Operation failed or was rolled back on all servers.");
        }
    }

    private boolean isDomainOperation(final ModelNode operation) {
        final PathAddress address = PathAddress.pathAddress(operation.require(OP_ADDR));
        return address.size() == 0 || !address.getElement(0).getKey().equals(HOST);
    }

    private class HostServer implements Comparable<HostServer> {
        private final String hostName;
        private final String serverName;
        private final ModelNode result;

        private HostServer(String hostName, String serverName, ModelNode result) {
            this.hostName = hostName;
            this.serverName = serverName;
            this.result = result;
        }

        public int compareTo(HostServer hostServer) {
            int hostCompare = hostName.compareTo(hostServer.hostName);
            if (hostCompare != 0) {
                return hostCompare;
            }
            return serverName.compareTo(hostServer.serverName);
        }
    }
}
