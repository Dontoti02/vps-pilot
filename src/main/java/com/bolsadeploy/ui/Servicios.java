package com.bolsadeploy.ui;

import com.bolsadeploy.application.AppContext;
import com.bolsadeploy.application.DeploymentService;
import com.bolsadeploy.application.MaintenanceService;
import com.bolsadeploy.ui.dialogos.HostKeyPromptFx;

/**
 * Contenedor de servicios para desacoplar las vistas de la inicialización y paso repetitivo de instancias.
 */
public class Servicios {

    private final AppContext app;
    private final DeploymentService despliegues;
    private final MaintenanceService mantenimiento;
    private final HostKeyPromptFx prompt;
    private final BolsaDeployApp mainApp;

    public Servicios(AppContext app, BolsaDeployApp mainApp) {
        this.app = app;
        this.mainApp = mainApp;
        this.despliegues = new DeploymentService(app);
        this.mantenimiento = new MaintenanceService(app);
        this.prompt = new HostKeyPromptFx();
    }

    public AppContext app() {
        return app;
    }

    public DeploymentService despliegues() {
        return despliegues;
    }

    public MaintenanceService mantenimiento() {
        return mantenimiento;
    }

    public HostKeyPromptFx prompt() {
        return prompt;
    }

    public BolsaDeployApp mainApp() {
        return mainApp;
    }
}

