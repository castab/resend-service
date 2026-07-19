import type { Metadata } from 'next';
import 'swagger-ui-react/swagger-ui.css';
import { SwaggerDocs } from './swagger-docs';

export const metadata: Metadata = {
  title: 'Webhook API documentation | resend-service',
  description: 'Interactive OpenAPI documentation for the webhook service',
};

export default function ApiDocumentationPage() {
  return <SwaggerDocs />;
}
