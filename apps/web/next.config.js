/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Resolve and transpile the shared workspace package from source.
  transpilePackages: ['@ihrms/shared'],
};

module.exports = nextConfig;
