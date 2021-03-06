/*
 Copyright 2016 Microsoft, Inc.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
 
 http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.microsoft.azure.util;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import com.microsoft.azure.vmagent.AzureVMManagementServiceDelegate;
import com.microsoft.azure.vmagent.Messages;
import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.exceptions.AzureCredentialsValidationException;
import hudson.Extension;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.Secret;
import java.util.Collections;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/*
Can't move AzureCredentials in the new package because it will break backwards compatibility.
Should be move once we upgrade to the credentials plugin version that includes this PR https://github.com/jenkinsci/credentials-plugin/pull/75
*/ 

public class AzureCredentials extends BaseStandardCredentials {

    public static final Logger LOGGER = Logger.getLogger(AzureCredentials.class.getName());

    public static class ServicePrincipal implements java.io.Serializable {

        private final Secret subscriptionId;
        private final Secret clientId;
        private final Secret clientSecret;
        private final Secret oauth2TokenEndpoint; //keeping this for backwards compatibility
        private final String serviceManagementURL;
        private final Secret tenant;
        private final String authenticationEndpoint;
        private final String resourceManagerEndpoint;
        private final String graphEndpoint;

        public String getSubscriptionId() {
            return (subscriptionId == null) ? "" : subscriptionId.getPlainText();
        }

        public String getClientId() {
            return (clientId == null) ? "" : clientId.getPlainText();
        }

        public String getClientSecret() {
            return (clientSecret == null) ? "" : clientSecret.getPlainText();
        }

        public String getTenant() {
            if (tenant == null) {
                return ServicePrincipal.getTenantFromTokenEndpoint(oauth2TokenEndpoint != null ? oauth2TokenEndpoint.getPlainText():"");
            } else {
                return tenant.getPlainText();
            }
        }

        public String getServiceManagementURL() {
            if (serviceManagementURL == null) {
                return Constants.DEFAULT_MANAGEMENT_URL;
            } else {
                return serviceManagementURL;
            }
        }

        public String getAuthenticationEndpoint() {
            if (authenticationEndpoint == null) {
                return Constants.DEFAULT_AUTHENTICATION_ENDPOINT;
            } else {
                return authenticationEndpoint;
            }
        }

        public String getResourceManagerEndpoint() {
            if (resourceManagerEndpoint == null) {
                return Constants.DEFAULT_RESOURCE_MANAGER_ENDPOINT;
            } else {
                return resourceManagerEndpoint;
            }
        }

        public String getGraphEndpoint() {
            if (graphEndpoint == null) {
                return Constants.DEFAULT_GRAPH_ENDPOINT;
            } else {
                return graphEndpoint;
            }
        }

        public ServicePrincipal(
                String subscriptionId,
                String clientId,
                String clientSecret,
                String oauth2TokenEndpoint,
                String serviceManagementURL,
                String authenticationEndpoint,
                String resourceManagerEndpoint,
                String graphEndpoint) {
            this.subscriptionId = Secret.fromString(subscriptionId);
            this.clientId = Secret.fromString(clientId);
            this.clientSecret = Secret.fromString(clientSecret);
            this.oauth2TokenEndpoint = Secret.fromString(oauth2TokenEndpoint);
            this.tenant = Secret.fromString(ServicePrincipal.getTenantFromTokenEndpoint(oauth2TokenEndpoint));
            this.serviceManagementURL = StringUtils.isBlank(serviceManagementURL)
                    ? Constants.DEFAULT_MANAGEMENT_URL
                    : serviceManagementURL;
            this.authenticationEndpoint = StringUtils.isBlank(authenticationEndpoint)
                    ? Constants.DEFAULT_AUTHENTICATION_ENDPOINT
                    : authenticationEndpoint;
            this.resourceManagerEndpoint = StringUtils.isBlank(resourceManagerEndpoint)
                    ? Constants.DEFAULT_RESOURCE_MANAGER_ENDPOINT
                    : resourceManagerEndpoint;
            this.graphEndpoint = StringUtils.isBlank(graphEndpoint)
                    ? Constants.DEFAULT_GRAPH_ENDPOINT
                    : graphEndpoint;
        }

        public ServicePrincipal() {
            this.subscriptionId = Secret.fromString("");
            this.clientId = Secret.fromString("");
            this.clientSecret = Secret.fromString("");
            this.oauth2TokenEndpoint = Secret.fromString("");
            this.tenant = Secret.fromString("");
            this.serviceManagementURL = Constants.DEFAULT_MANAGEMENT_URL;
            this.authenticationEndpoint = Constants.DEFAULT_AUTHENTICATION_ENDPOINT;
            this.resourceManagerEndpoint = Constants.DEFAULT_RESOURCE_MANAGER_ENDPOINT;
            this.graphEndpoint = Constants.DEFAULT_GRAPH_ENDPOINT;
        }

        public boolean isBlank() {
            return StringUtils.isBlank(subscriptionId.getPlainText())
                    || StringUtils.isBlank(clientId.getPlainText())
                    || StringUtils.isBlank(oauth2TokenEndpoint.getPlainText())
                    || StringUtils.isBlank(clientSecret.getPlainText());
        }

        public boolean validate(String resourceGroupName, String maxVMLimit, String deploymentTimeout) throws AzureCredentialsValidationException {
            if (StringUtils.isBlank(subscriptionId.getPlainText())) {
                throw new AzureCredentialsValidationException("Error: Subscription ID is missing");
            }
            if (StringUtils.isBlank(clientId.getPlainText())) {
                throw new AzureCredentialsValidationException("Error: Native Client ID is missing");
            }
            if (StringUtils.isBlank(clientSecret.getPlainText())) {
                throw new AzureCredentialsValidationException("Error: Azure Password is missing");
            }
            if (StringUtils.isBlank(oauth2TokenEndpoint.getPlainText())) {
                throw new AzureCredentialsValidationException("Error: OAuth 2.0 Token Endpoint is missing");
            }
            if (StringUtils.isBlank(getTenant())) {
                throw new AzureCredentialsValidationException("Error: OAuth 2.0 Token Endpoint is malformed");
            }

            String response = AzureVMManagementServiceDelegate.verifyConfiguration(this, resourceGroupName, maxVMLimit, deploymentTimeout);
            if (!Constants.OP_SUCCESS.equalsIgnoreCase(response)) {
                throw new AzureCredentialsValidationException(response);
            }
            
            return true;
        }

        private static String getTenantFromTokenEndpoint(String oauth2TokenEndpoint)
        {
            if(!oauth2TokenEndpoint.matches("https://[a-zA-Z0-9\\.]*/[a-z0-9\\-]*/?.*$")) {
                return "";
            } else {
                final String[] parts = oauth2TokenEndpoint.split("/");
                if (parts.length < 4) {
                    return "";
                } else {
                    return parts[3];
                }
            }
        }

    }

    public final ServicePrincipal data;

    @DataBoundConstructor
    public AzureCredentials(
            CredentialsScope scope,
            String id,
            String description,
            String subscriptionId,
            String clientId,
            String clientSecret,
            String oauth2TokenEndpoint,
            String serviceManagementURL,
            String authenticationEndpoint,
            String resourceManagerEndpoint,
            String graphEndpoint) {
        super(scope, id, description);
        data = new ServicePrincipal(subscriptionId, clientId, clientSecret, oauth2TokenEndpoint, serviceManagementURL, authenticationEndpoint, resourceManagerEndpoint, graphEndpoint);
    }

    public static AzureCredentials.ServicePrincipal getServicePrincipal(final String credentialsId) {
        AzureCredentials creds = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(AzureCredentials.class, Jenkins.getInstance(), ACL.SYSTEM, Collections.<DomainRequirement>emptyList()),
                CredentialsMatchers.withId(credentialsId));
        if (creds == null) {
            return new AzureCredentials.ServicePrincipal();
        }
        return creds.data;
    }

    public String getSubscriptionId() {
        return data.subscriptionId.getEncryptedValue();
    }

    public String getClientId() {
        return data.clientId.getEncryptedValue();
    }

    public String getClientSecret() {
        return data.clientSecret.getEncryptedValue();
    }

    public String getOauth2TokenEndpoint() {
        return data.oauth2TokenEndpoint.getEncryptedValue();
    }

    public String getServiceManagementURL() {
        return data.serviceManagementURL;
    }

    public String getAuthenticationEndpoint() {
        return data.authenticationEndpoint;
    }

    public String getResourceManagerEndpoint() {
        return data.resourceManagerEndpoint;
    }

    public String getGraphEndpoint() {
        return data.graphEndpoint;
    }

    @Extension
    public static class DescriptorImpl extends BaseStandardCredentials.BaseStandardCredentialsDescriptor {

        @Override
        public String getDisplayName() {
            return "Microsoft Azure VM Agents";
        }

        public String getDefaultServiceManagementURL() {
            return Constants.DEFAULT_MANAGEMENT_URL;
        }

        public String getDefaultAuthenticationEndpoint() {
            return Constants.DEFAULT_AUTHENTICATION_ENDPOINT;
        }

        public String getDefaultResourceManagerEndpoint() {
            return Constants.DEFAULT_RESOURCE_MANAGER_ENDPOINT;
        }

        public String getDefaultGraphEndpoint() {
            return Constants.DEFAULT_GRAPH_ENDPOINT;
        }

        public FormValidation doVerifyConfiguration(
                @QueryParameter String subscriptionId,
                @QueryParameter String clientId,
                @QueryParameter String clientSecret,
                @QueryParameter String oauth2TokenEndpoint,
                @QueryParameter String serviceManagementURL,
                @QueryParameter String authenticationEndpoint,
                @QueryParameter String resourceManagerEndpoint,
                @QueryParameter String graphEndpoint) {

            AzureCredentials.ServicePrincipal servicePrincipal = new AzureCredentials.ServicePrincipal(subscriptionId, clientId, clientSecret, oauth2TokenEndpoint, 
                                                                serviceManagementURL, authenticationEndpoint, resourceManagerEndpoint, graphEndpoint);
            try {
                servicePrincipal.validate(Constants.DEFAULT_RESOURCE_GROUP_NAME, Integer.toString(Constants.DEFAULT_MAX_VM_LIMIT), 
                        Integer.toString(Constants.DEFAULT_DEPLOYMENT_TIMEOUT_SEC));
            } catch (AzureCredentialsValidationException e) {
                return FormValidation.error(e.getMessage());
            }

            return FormValidation.ok(Messages.Azure_Config_Success());
        }

    }
}
