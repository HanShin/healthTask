/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/client" />

interface ImportMetaEnv {
  readonly VITE_SUPABASE_URL?: string;
  readonly VITE_SUPABASE_ANON_KEY?: string;
  readonly VITE_RECOMMENDATION_TEMPLATES_REFRESH_MINUTES?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
