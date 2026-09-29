#!/usr/bin/env node

/**
 * Helper script to generate .env file from Keycloak secrets
 * Reads from keycloak/output/secrets.txt and creates/updates .env
 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const PROJECT_ROOT = path.resolve(__dirname, '..');
const SECRETS_FILE = path.join(PROJECT_ROOT, 'keycloak', 'output', 'secrets.txt');
const ENV_FILE = path.join(__dirname, '.env');
const ENV_EXAMPLE = path.join(__dirname, '.env.example');

// Default values
const defaults = {
  VITE_KEYCLOAK_URL: 'http://keycloak.payment.svc.cluster.local:8080',
  VITE_KEYCLOAK_REALM: 'ecommerce-platform',
  VITE_KEYCLOAK_CLIENT_ID: 'payment-service',
  VITE_KEYCLOAK_CLIENT_SECRET: '',
  VITE_API_BASE_URL: 'http://localhost',
};

function readSecrets() {
  if (!fs.existsSync(SECRETS_FILE)) {
    console.error(`❌ Secrets file not found: ${SECRETS_FILE}`);
    console.error('💡 Please run ./keycloak/provision-keycloak.sh first');
    process.exit(1);
  }

  const content = fs.readFileSync(SECRETS_FILE, 'utf8');
  const secrets = {};
  
  for (const line of content.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    
    const match = trimmed.match(/^([^=]+)=(.*)$/);
    if (match) {
      const key = match[1].trim();
      const value = match[2].trim();
      secrets[key] = value;
    }
  }

  return secrets;
}



/**
 * Load-balancer IP of a service, found the same way as the terminal commands in
 * docs/how-to-start.md and keycloak/get-token.sh. Returns null when kubectl cannot tell.
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

  // Read secrets
  const secrets = readSecrets();
  const clientSecret = secrets.PAYMENT_SERVICE_CLIENT_SECRET;
  
  if (!clientSecret) {
    console.error('❌ PAYMENT_SERVICE_CLIENT_SECRET not found in secrets file');
    process.exit(1);
  }


  // Read existing .env to preserve custom values
  const existing = readExistingEnv();

  // Build env object
  const env = {
    ...defaults,
    ...existing, // Preserve existing custom values
    VITE_KEYCLOAK_CLIENT_SECRET: clientSecret,
    // Always use correct client ID (override any existing wrong value)
    VITE_KEYCLOAK_CLIENT_ID: defaults.VITE_KEYCLOAK_CLIENT_ID,
  };

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
    `VITE_KEYCLOAK_CLIENT_ID=${env.VITE_KEYCLOAK_CLIENT_ID}`,
    `VITE_KEYCLOAK_CLIENT_SECRET=${env.VITE_KEYCLOAK_CLIENT_SECRET}`,
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
  console.log(`   Client ID: ${env.VITE_KEYCLOAK_CLIENT_ID}`);
  console.log(`   Client Secret: ${clientSecret.substring(0, 8)}... (hidden)`);
  console.log(`   API Base URL: ${env.VITE_API_BASE_URL}\n`);
  console.log('💡 You can now run: npm run dev');
}

generateEnv();

