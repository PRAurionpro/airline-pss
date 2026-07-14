# PSS Platform

## Overview

PSS Platform (Passenger Service System) is a modern, multi-tenant airline platform developed by Aurionpro. It enables airlines to manage the complete passenger journey, from flight search and booking to payment, check-in, boarding, and loyalty management.

The platform is built using a microservices architecture with contract-first APIs, event-driven communication, and automated governance to ensure scalability, maintainability, and reliability.

---

## Key Features

- Flight Search & Booking
- Pricing & Inventory Management
- Payment Gateway Integration (Razorpay Sandbox)
- Order Management
- NDC Distribution
- Corporate Travel Portal
- Loyalty Program
- Customer Portal
- Departure Control System (DCS)
- Revenue Management
- Customer Personalization (CDP)
- WhatsApp Conversational Commerce
- Reporting & Analytics Dashboard

---

## Architecture

- Microservices Architecture
- Event-Driven Communication (Kafka/Redpanda)
- REST APIs (OpenAPI)
- Docker-based Deployment
- Contract-First Development
- Multi-Tenant Design
- CI/CD Governance & Fitness Functions

---

## Technology Stack

### Backend
- Java 21
- Spring Boot
- Gradle

### Frontend
- React
- HTML
- CSS
- JavaScript

### Database
- Service-specific Databases

### Messaging
- Kafka / Redpanda

### Containerization
- Docker
- Docker Compose

### API Documentation
- OpenAPI

---

## Prerequisites

Before running the application, ensure you have:

- Java JDK 21
- Docker Desktop
- Docker Compose
- Git
- Gradle Wrapper (included)
- GitHub Account

---

## Installation

### Clone Repository

```bash
git clone https://github.com/<your-org>/pss-platform.git
cd pss-platform
```

### Build

```bash
./gradlew build
```

### Start Application

```bash
docker compose -f docker-compose.demo.yml -f docker-compose.demo-full.yml up -d
```

### Seed Demo Data

```bash
./scripts/demo-reset-full.sh
```

---

## Application URL

```
http://localhost:8093
```

---

## Project Modules

- Offer Service
- Order Service
- Inventory Service
- Pricing Service
- Payment Service
- Distribution Service
- Corporate Portal
- Loyalty Service
- Customer Portal
- Reporting Service
- Revenue Management
- Departure Control System
- Customer Data Platform
- WhatsApp Integration

---

## Current Status

- Phase 1 – Complete
- Phase 2 – Complete
- Demo Product Program – In Progress
- Workstream 3 – Next Priority
- Workstream 4 – Planned
- Workstream 5 – Planned

---

## Repository Structure

```
docs/
scripts/
docker/
services/
gateway/
platform/
README.md
```

---

## Development Workflow

1. Create Feature Branch
2. Implement Changes
3. Run Local Build
4. Verify Functionality
5. Create Pull Request
6. Code Review
7. Merge into Main

---

## Environment Variables

Create a `.env` file with required secrets.

Example:

```
RAZORPAY_KEY=
RAZORPAY_SECRET=
WEBHOOK_SECRET=
```

> Do not commit `.env` to Git.

---

## Documentation

Important documents:

- README.md
- LATEST_STATUS.md
- BUILD_PLAN_DEMO_PRODUCT.md
- Architecture Decision Records (ADR)
- DEMO_SCRIPT.md

---

## Branch Strategy

```
main
develop
feature/*
bugfix/*
release/*
hotfix/*
```

---

## Best Practices

- Follow Contract-First Development
- Keep APIs backward compatible
- Write unit and integration tests
- Validate OpenAPI contracts
- Verify UI flows manually before merging
- Use Pull Requests for all code changes

---

## Contributing

1. Fork or create a feature branch.
2. Commit your changes.
3. Push to GitHub.
4. Create a Pull Request.
5. Obtain code review approval before merging.

---

## License

Internal Aurionpro Project.

---

## Maintainers

Aurionpro Development Team

---

## Contact

For project onboarding or repository access, contact the project owner or engineering lead.
