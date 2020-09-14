/*
 * Copyright 2020 Esri.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.esri.samples.edit_with_branch_versioning;

import java.util.List;
import java.util.concurrent.ExecutionException;

import javafx.fxml.FXML;
import javafx.geometry.Point2D;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;

import com.esri.arcgisruntime.arcgisservices.ServiceVersionInfo;
import com.esri.arcgisruntime.arcgisservices.ServiceVersionParameters;
import com.esri.arcgisruntime.arcgisservices.VersionAccess;
import com.esri.arcgisruntime.concurrent.ListenableFuture;
import com.esri.arcgisruntime.data.ArcGISFeature;
import com.esri.arcgisruntime.data.FeatureTableEditResult;
import com.esri.arcgisruntime.data.ServiceFeatureTable;
import com.esri.arcgisruntime.data.ServiceGeodatabase;
import com.esri.arcgisruntime.geometry.Point;
import com.esri.arcgisruntime.layers.FeatureLayer;
import com.esri.arcgisruntime.loadable.LoadStatus;
import com.esri.arcgisruntime.mapping.ArcGISMap;
import com.esri.arcgisruntime.mapping.Basemap;
import com.esri.arcgisruntime.mapping.GeoElement;
import com.esri.arcgisruntime.mapping.Viewpoint;
import com.esri.arcgisruntime.mapping.view.IdentifyLayerResult;
import com.esri.arcgisruntime.mapping.view.MapView;
import com.esri.arcgisruntime.security.AuthenticationManager;
import com.esri.arcgisruntime.security.DefaultAuthenticationChallengeHandler;

public class EditWithBranchVersioningController {

  @FXML private ComboBox<VersionAccess> accessComboBox;
  @FXML private Button createVersionButton;
  @FXML private VBox createVersionVBox;
  @FXML private Label currentVersionLabel;
  @FXML private ComboBox<String> damageTypeComboBox;
  @FXML private TextField descriptionTextField;
  @FXML private VBox editFeatureVBox;
  @FXML private MapView mapView;
  @FXML private TextField nameTextField;
  @FXML private ProgressIndicator progressIndicator;
  @FXML private Button switchVersionButton;

  private String defaultVersion;
  private FeatureLayer featureLayer; // keeps loadable in scope to avoid garbage collection
  private ArcGISFeature selectedFeature; // keeps loadable in scope to avoid garbage collection
  private ServiceFeatureTable serviceFeatureTable;
  private ServiceGeodatabase serviceGeodatabase; // keeps loadable in scope to avoid garbage collection
  private String userCreatedVersion;

  public void initialize() {
    try {
      // create a map with the streets vector basemap
      ArcGISMap map = new ArcGISMap(Basemap.createStreetsVector());

      // create a map view and set its map
      mapView.setMap(map);

      // configure the initial UI settings
      createVersionButton.setDisable(true);
      switchVersionButton.setDisable(true);
      editFeatureVBox.setDisable(true);

      // add the version access types to the combo box
      accessComboBox.getItems().addAll(VersionAccess.PUBLIC, VersionAccess.PROTECTED, VersionAccess.PRIVATE);

      // add the damage types to the combo box and handle selection
      damageTypeComboBox.getItems().addAll("Destroyed", "Inaccessible", "Major", "Minor", "Affected");
      damageTypeComboBox.getSelectionModel().selectedItemProperty().addListener((o, p, n) -> {
        if (!selectedFeature.getAttributes().get("TYPDAMAGE").equals(n)) {
          try {
            selectedFeature.getAttributes().put("TYPDAMAGE", damageTypeComboBox.getValue());
            updateFeature(selectedFeature);
          } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, "Feature attributes failed to update").show();
          }
        }
      });

      // handle authentication for the service geodatabase
      AuthenticationManager.setAuthenticationChallengeHandler(new DefaultAuthenticationChallengeHandler());

      // create and load a service geodatabase
      serviceGeodatabase = new ServiceGeodatabase("https://sampleserver7.arcgisonline" +
        ".com/arcgis/rest/services/DamageAssessment/FeatureServer");
      serviceGeodatabase.loadAsync();
      serviceGeodatabase.addDoneLoadingListener(() -> {
        if (serviceGeodatabase.getLoadStatus() == LoadStatus.LOADED) {

          // when the service geodatabase has loaded get the default version
          defaultVersion = serviceGeodatabase.getDefaultVersionName();

          // get the service feature table from the service geodatabase
          if (serviceGeodatabase.getTable(0) != null) {
            serviceFeatureTable = serviceGeodatabase.getTable(0);

            // create a feature layer from the service feature table and add it to the map
            featureLayer = new FeatureLayer(serviceFeatureTable);
            map.getOperationalLayers().add(featureLayer);
            featureLayer.addDoneLoadingListener(() -> {
              if (featureLayer.getLoadStatus() == LoadStatus.LOADED) {

                // when the feature layer has loaded set the viewpoint and update the UI
                mapView.setViewpointAsync(new Viewpoint(featureLayer.getFullExtent()));
                progressIndicator.setVisible(false);
                createVersionButton.setDisable(false);
                currentVersionLabel.setText("Current version: " + serviceGeodatabase.getVersionName());
              } else {
                new Alert(Alert.AlertType.ERROR, "Feature layer failed to load").show();
              }
            });
          } else {
            new Alert(Alert.AlertType.ERROR, "Unable to get the service feature table").show();
          }
        } else {
          progressIndicator.setVisible(false);
          new Alert(Alert.AlertType.ERROR, "Service geodatabase failed to load").show();
        }
      });

      // listen to clicks on the map to select or move features
      mapView.setOnMouseClicked(event -> {
        if (event.isStillSincePress() && event.getButton() == MouseButton.PRIMARY) {
          // reset the UI
          featureLayer.clearSelection();
          editFeatureVBox.setDisable(true);

          // select the clicked feature
          Point2D point = new Point2D(event.getX(), event.getY());
          selectFeature(point);
        }

        if (event.isStillSincePress() && event.getButton() == MouseButton.SECONDARY) {
          // if a feature is selected and the current branch version is not the default, update the feature's geometry
          if (selectedFeature != null && !serviceGeodatabase.getVersionName().equals(defaultVersion)) {
            Point2D point = new Point2D(event.getX(), event.getY());
            Point mapPoint = mapView.screenToLocation(point);
            selectedFeature.setGeometry(mapPoint);
            updateFeature(selectedFeature);
          }
        }
      });
    } catch (Exception e) {
      // on any error, display the stack trace.
      e.printStackTrace();
    }
  }

  /**
   * Create a new branch version using user defined values.
   */
  @FXML
  private void handleCreateVersionButtonClicked() {

    // validate version name input
    if (nameTextField.getText().contains(".") || nameTextField.getText().contains(";") || nameTextField.getText().contains("'") ||
      nameTextField.getText().contains("\"")) {
      new Alert(Alert.AlertType.ERROR, "Please enter a valid version name.\nThe name cannot contain the following characters:\n. ; ' \" ").show();
      return;
    } else if (nameTextField.getText().length() > 0 && Character.isWhitespace(nameTextField.getText().charAt(0))) {
      new Alert(Alert.AlertType.ERROR, "Version name cannot begin with a space").show();
      return;
    } else if (nameTextField.getText().length() > 62) {
      new Alert(Alert.AlertType.ERROR, "Version name must not exceed 62 characters").show();
      return;
    } else if (nameTextField.getText().length() == 0) {
      new Alert(Alert.AlertType.ERROR, "Please enter a version name").show();
      return;
    }

    // validate version access input
    if (accessComboBox.getSelectionModel().getSelectedItem() == null) {
      new Alert(Alert.AlertType.ERROR, "Please select an access level").show();
      return;
    }

    // set the user defined name, access level and description as service version parameters
    ServiceVersionParameters newVersionParameters = new ServiceVersionParameters();
    newVersionParameters.setName(nameTextField.getText());
    newVersionParameters.setAccess(accessComboBox.getSelectionModel().getSelectedItem());
    newVersionParameters.setDescription(descriptionTextField.getText());

    // update the UI
    createVersionButton.setText("Creating version....");
    createVersionButton.setDisable(true);

    // create a new version with the specified parameters
    ListenableFuture<ServiceVersionInfo> newVersion = serviceGeodatabase.createVersionAsync(newVersionParameters);
    newVersion.addDoneListener(() -> {
      try {
        // get the name of the created version and switch to it
        ServiceVersionInfo createdVersionInfo = newVersion.get();
        userCreatedVersion = createdVersionInfo.getName();
        switchVersion(userCreatedVersion);

        // hide the form from the UI as the sample only allows 1 version to be created
        createVersionVBox.setVisible(false);
        switchVersionButton.setDisable(false);

      } catch (Exception ex) {
        // if there is an error creating a new version, display an alert and reset the UI
        if (ex.getCause().toString().contains("The version already exists")) {
          new Alert(Alert.AlertType.ERROR, "A service version with this name already exists.\nPlease enter a unique version name").show();
        } else {
          new Alert(Alert.AlertType.ERROR, "Error creating new version").show();
        }
        createVersionButton.setText("Create version");
        createVersionButton.setDisable(false);
      }
    });
  }

  /**
   * Apply local edits to the service geodatabase and switch branch version.
   */
  @FXML
  private void handleSwitchVersionButtonClicked() {
    if (serviceGeodatabase.getVersionName().equals(userCreatedVersion)) {
      // if the user created version has local edits, apply the edits to the service geodatabase
      if (serviceGeodatabase.hasLocalEdits()) {
        ListenableFuture<List<FeatureTableEditResult>> resultOfApplyEdits = serviceGeodatabase.applyEditsAsync();
        resultOfApplyEdits.addDoneListener(() -> {
          try {
            // check if the server edit was successful
            List<FeatureTableEditResult> edits = resultOfApplyEdits.get();
            if (edits == null || edits.isEmpty()) {
              new Alert(Alert.AlertType.ERROR, "Error applying edits on server").show();
            } else {
              // if the edits were successful, switch to the default version
              switchVersion(defaultVersion);
            }
          } catch (InterruptedException | ExecutionException e) {
            new Alert(Alert.AlertType.ERROR, "Error applying edits on server").show();
          }
        });
      } else {
        // if there are no local edits, switch to the default version
        switchVersion(defaultVersion);
      }
    } else if (serviceGeodatabase.getVersionName().equals(defaultVersion)) {
      // if the current version is the default version, switch to the user created version
      switchVersion(userCreatedVersion);
    }
  }

  /**
   * Switch the active branch version.
   *
   * @param versionName name of the version to switch to
   */
  private void switchVersion(String versionName) {
    ListenableFuture<Void> switchVersionResult = serviceGeodatabase.switchVersionAsync(versionName);
    switchVersionResult.addDoneListener(() -> {
      // check if the active version has switched successfully and update the UI
      if (serviceGeodatabase.getVersionName().equals(versionName)) {
        currentVersionLabel.setText("Current version: " + serviceGeodatabase.getVersionName());
        editFeatureVBox.setDisable(true);
      } else {
        new Alert(Alert.AlertType.ERROR, "Error switching version.").show();
      }
    });
  }

  /**
   * Select a feature if one exists where the user clicked.
   *
   * @param point location where the user clicked
   */
  private void selectFeature(Point2D point) {
    ListenableFuture<IdentifyLayerResult> identifyLayerResultFuture = mapView.identifyLayerAsync(featureLayer, point, 1, false);
    identifyLayerResultFuture.addDoneListener(() -> {
      try {
        var identifyLayerResult = identifyLayerResultFuture.get();
        List<GeoElement> identifiedElements = identifyLayerResult.getElements();
        if (!identifiedElements.isEmpty()) {
          var element = identifiedElements.get(0);
          if (element instanceof ArcGISFeature) {
            // get the selected feature
            selectedFeature = (ArcGISFeature) element;
            featureLayer.selectFeature(selectedFeature);
            selectedFeature.loadAsync();
            selectedFeature.addDoneLoadingListener(() -> {
              if (selectedFeature.getLoadStatus() == LoadStatus.LOADED) {
                // when a feature has been selected set the damage combo box to the feature's attribute value
                damageTypeComboBox.getSelectionModel().select((String) selectedFeature.getAttributes().get("TYPDAMAGE"));

                // enable feature editing UI if not on the default branch version
                if (!serviceGeodatabase.getVersionName().equals(defaultVersion)) {
                  editFeatureVBox.setDisable(false);
                }
              } else {
                new Alert(Alert.AlertType.ERROR, "Feature failed to load.").show();
              }
            });
          }
        }
      } catch (InterruptedException | ExecutionException e) {
        new Alert(Alert.AlertType.ERROR, "Failed to identify the feature").show();
      }
    });
  }

  /**
   * Update the selected feature in the service feature table.
   *
   * @param selectedFeature the selected feature to be updated
   */
  private void updateFeature(ArcGISFeature selectedFeature) {
    if (serviceFeatureTable.canUpdate(selectedFeature) && !serviceGeodatabase.getVersionName().equals(defaultVersion)) {
      // update the feature in the feature table
      ListenableFuture<Void> editResult = serviceFeatureTable.updateFeatureAsync(selectedFeature);
      editResult.addDoneListener(() -> {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setHeaderText("Feature updated");
        alert.setContentText("Changes will be synced to the service geodatabase\nwhen you switch branch.");
        alert.show();
      });
    } else {
      new Alert(Alert.AlertType.ERROR, "Feature cannot be updated").show();
    }
  }

  /**
   * Disposes application resources.
   */
  void terminate() {
    if (mapView != null) {
      mapView.dispose();
    }
  }
}
