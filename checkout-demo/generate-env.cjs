#!/usr/bin/env node

/**
 * Helper script to generate the .env file: the cluster's addresses and the merchant backends' credentials
 * Creates/updates .env with the Keycloak and API addresses of the running cluster
 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const PROJECT_ROOT = path.resolve(__dirname, '..');
const ENV_FILE = path.join(__dirname, '.env');
const ENV_EXAMPLE = path.join(__dirname, '.env.example');

// Default values
const defaults = {
  VITE_KEYCLOAK_URL: 'http://keycloak.payment.svc.cluster.local:8080',
  VITE_KEYCLOAK_REALM: 'ecommerce-platform',
  VITE_API_BASE_URL: 'http://localhost',
};




/**
 * Load-balancer IP of a service, found the same way as the terminal commands in
 * docs/how-to-start.md and keycloak/get-access-token.sh. Returns null when kubectl cannot tell.
 */
function loadBalancerIp(namespace, service) {
  try {
    const ip = execSync(
      `kubectl get svc ${service} -n ${namespace} -o jsonpath='{.status.loadBalancer.ingress[0].ip}'`,
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 10000 }
    ).trim();
    if (ip) {
      return ip;
    }
    return null;
  } catch (e) {
    return null;
  }
}

function readExistingEnv() {
  const env = {};
  if (fs.existsSync(ENV_FILE)) {
    const content = fs.readFileSync(ENV_FILE, 'utf8');
    for (const line of content.split('\n')) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith('#')) continue;
      
      const match = trimmed.match(/^([^=]+)=(.*)$/);
      if (match) {
        const key = match[1].trim();
        const value = match[2].trim();
        env[key] = value;
      }
    }
  }
  return env;
}

function generateEnv() {
  console.log('🔧 Generating .env file...\n');

  // Read existing .env to preserve custom values
  const existing = readExistingEnv();

  // Build env object
  const env = {
    ...defaults,
    ...existing, // Preserve existing custom values
  };

  // The merchants the proxy may act for: every merchant backend client loaded into Keycloak by
  // keycloak/setup-keycloak.sh (keycloak/realm/merchants-seed.json), with its local secret.
  const seedFile = path.join(PROJECT_ROOT, 'keycloak', 'realm', 'merchants-seed.json');
  const credentials = [];
  for (const client of JSON.parse(fs.readFileSync(seedFile, 'utf8')).clients) {
    credentials.push(`${client.clientId.replace(/^merchant-api-/, '')}:${client.secret}`);
  }
  env.MERCHANT_CREDENTIALS = credentials.join(',');

  // Addresses come from the running cluster, like the curl commands in how-to-start.md.
  // They replace older values in .env, because a redeploy can change them.
  const keycloakIp = loadBalancerIp('payment', 'keycloak');
  if (keycloakIp) {
    env.VITE_KEYCLOAK_URL = `http://${keycloakIp}:8080`;
  } else {
    console.warn(`⚠️  Could not get the Keycloak IP from kubectl, using ${env.VITE_KEYCLOAK_URL}`);
  }
  const ingressIp = loadBalancerIp('ingress-controller', 'ingress-nginx-controller');
  if (ingressIp) {
    env.VITE_API_BASE_URL = `http://${ingressIp}`;
  } else {
    console.warn(`⚠️  Could not get the ingress IP from kubectl, using ${env.VITE_API_BASE_URL}`);
  }



  // Generate .env content
  const lines = [
    '# Keycloak Configuration',
    `VITE_KEYCLOAK_URL=${env.VITE_KEYCLOAK_URL}`,
    `VITE_KEYCLOAK_REALM=${env.VITE_KEYCLOAK_REALM}`,
    ...(env.MERCHANT_CREDENTIALS ? [`MERCHANT_CREDENTIALS=${env.MERCHANT_CREDENTIALS}`] : []),
    '',
    '# Payment API Configuration',
    `VITE_API_BASE_URL=${env.VITE_API_BASE_URL}`,
    '',
    '# Stripe publishable key (browser only; never put a secret key sk_... here)',
    `VITE_STRIPE_PUBLISHABLE_KEY=${env.VITE_STRIPE_PUBLISHABLE_KEY || ''}`,
    ''
  ];

  // Write .env file
  fs.writeFileSync(ENV_FILE, lines.join('\n'), 'utf8');

  console.log('✅ .env file generated successfully!');
  console.log(`   Location: ${ENV_FILE}\n`);
  console.log('📋 Configuration:');
  console.log(`   Keycloak URL: ${env.VITE_KEYCLOAK_URL}`);
  console.log(`   Realm: ${env.VITE_KEYCLOAK_REALM}`);
  console.log(`   Merchants the proxy can act for: ${credentials.length} (${credentials.map((c) => c.split(':')[0]).join(', ')})`);
  console.log(`   API Base URL: ${env.VITE_API_BASE_URL}\n`);
  console.log('💡 You can now run: npm run dev');
}

generateEnv();

