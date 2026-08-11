# Rundeck HTTP Workflow Step Plugin

This plugin provides a way to send HTTP requests as a workflow step or
node step in a [Rundeck](https://rundeck.org) job.

This plugin is bundled with the Rundeck application, so no separate
installation is required. It is built and maintained as part of the
[rundeck/rundeck](https://github.com/rundeck/rundeck) project.

## Features

- HTTP methods: GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS
- Authentication: Basic, Bearer Token, or OAuth 2.0 (Client Credentials Grant)
- Project or Framework level configuration
- Support for self-signed SSL certificates

## Usage

Add "HTTP Workflow Step" or "HTTP Node Step" to a job as a workflow step
or node step, and configure the URL, method, headers, body, and
authentication as needed.

See the [HTTP Request Workflow Step documentation](https://docs.rundeck.com/docs/manual/jobs/job-plugins/workflow-steps/http-request.html)
for full reference on this plugin's options.

## Caveats

OAuth 2.0 only supports the Client Credentials Grant Type. The OAuth
configuration is per-project or per-framework. This means that each job
will share the entire project's or entire framework's credentials.
However, this allows those credentials to be externalized into the
framework configuration and avoids them being exported with projects.