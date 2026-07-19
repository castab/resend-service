'use client';

import dynamic from 'next/dynamic';

const SwaggerUI = dynamic(() => import('swagger-ui-react'), { ssr: false });

export function SwaggerDocs() {
  return <SwaggerUI url="/openapi.json" deepLinking />;
}
