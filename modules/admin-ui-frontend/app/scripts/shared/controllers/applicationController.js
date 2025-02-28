/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */
'use strict';

// The main controller that all other scopes inherit from (except isolated scopes).
angular.module('adminNg.controllers')
.controller('ApplicationCtrl', ['$scope', '$rootScope', '$location', '$window', 'AuthService', 'Notifications',
  'ResourceModal', 'VersionResource', 'HotkeysService', '$interval', 'RestServiceMonitor',
  'AdopterRegistrationResource',
  function ($scope, $rootScope, $location, $window, AuthService, Notifications, ResourceModal,
    VersionResource, HotkeysService, $interval, RestServiceMonitor, AdopterRegistrationResource){

    $scope.adopter = new AdopterRegistrationResource();

    $scope.bodyClicked = function () {
      angular.element('[old-admin-ng-dropdown]').removeClass('active');
    };

    var FEEDBACK_URL_PROPERTY = 'org.opencastproject.admin.feedback.url',
        DOCUMENTATION_URL_PROPERTY = 'org.opencastproject.admin.help.documentation.url',
        RESTDOCS_URL_PROPERTY = 'org.opencastproject.admin.help.restdocs.url',
        MEDIA_MODULE_URL_PROPERTY = 'org.opencastproject.admin.mediamodule.url';
    $scope.currentUser  = null;
    $scope.feedbackUrl = undefined;
    $scope.documentationUrl = undefined;
    $scope.restdocsUrl = undefined;
    $scope.mediaModuleUrl = undefined;
    RestServiceMonitor.run();
    $scope.services = RestServiceMonitor.getServiceStatus();
    $scope.studioReturnUrl = encodeURIComponent($location.absUrl());
    $scope.$on('$locationChangeSuccess', function($event, newUrl) {
      $scope.studioReturnUrl = encodeURIComponent(newUrl);
    });

    AuthService.getUser().$promise.then(function (user) {
      $scope.currentUser = user;

      if (angular.isDefined(user.org.properties[FEEDBACK_URL_PROPERTY])) {
        $scope.feedbackUrl = user.org.properties[FEEDBACK_URL_PROPERTY];
      }

      if (angular.isDefined(user.org.properties[DOCUMENTATION_URL_PROPERTY])) {
        $scope.documentationUrl = user.org.properties[DOCUMENTATION_URL_PROPERTY];
      }

      if (angular.isDefined(user.org.properties[RESTDOCS_URL_PROPERTY])) {
        $scope.restdocsUrl = user.org.properties[RESTDOCS_URL_PROPERTY];
      }

      if (angular.isDefined(user.org.properties[MEDIA_MODULE_URL_PROPERTY])) {
        $scope.mediaModuleUrl = user.org.properties[MEDIA_MODULE_URL_PROPERTY];
      }
    }).catch(angular.noop);

    //Running RestService on loop - updating $scope.service
    $interval(function(){
      RestServiceMonitor.run();
      $scope.service = RestServiceMonitor.getServiceStatus();
    }, 60000);

    $scope.toServices = function(event) {
      RestServiceMonitor.jumpToServices(event);
    };

    $scope.toDoc = function () {
      if ($scope.documentationUrl) {
        $window.open ($scope.documentationUrl);
      }
    };

    $scope.toRestDoc = function () {
      if ($scope.restdocsUrl) {
        $window.open ($scope.restdocsUrl);
      }
    };

    $rootScope.userIs = AuthService.userIsAuthorizedAs;

    // Restore open modals if any
    var params = $location.search();
    if (params.modal && params.resourceId) {
      ResourceModal.show(params.modal, params.resourceId, params.tab, params.action);
    }

    if (angular.isUndefined($rootScope.version)) {
      VersionResource.query(function(response) {
        if (response['last-modified']) {
          response['last-modified'] = new Date(response['last-modified']).toISOString().substr(0, 10);
        }
        $rootScope.version = response.version
          ? response
          : (angular.isArray(response.versions) ? response.versions[0] : {});
        if (!response.consistent) {
          $rootScope.version.buildNumber = 'inconsistent';
        }
      });
    }

    AdopterRegistrationResource.get({}, function(adopter) {
      // We exclude localhost to not show this to developers all the time.
      // We wouldn't get proper data from such instances anyway.
      if(adopter.dateModified == null && window.location.hostname != 'localhost') {
        ResourceModal.show('registration-modal');
        return;
      }
      if(adopter.registered === false) {
        var now = new Date();
        var lastModified = new Date(adopter.dateModified);
        var numberOfDaysPassed = Math.ceil((now - lastModified) / 8.64e7);
        if(numberOfDaysPassed > 30) {
          ResourceModal.show('registration-modal');
        }
      }
    });

    const checkPermission = (rolesWithPermission) => {
      let roles = AuthService.getRoles();
      return rolesWithPermission.some(i => roles.includes(i));
    };

    HotkeysService.activateUniversalHotkey('general.event_view', function (event) {
      event.preventDefault();
      $location.path('/events/events').replace();
    });

    HotkeysService.activateUniversalHotkey('general.series_view', function (event) {
      event.preventDefault();
      $location.path('/events/series').replace();
    });

    HotkeysService.activateUniversalHotkey('general.new_event', function (event) {
      let rolesWithPermission = ['ROLE_UI_EVENTS_CREATE', 'ROLE_ADMIN'];
      // only useres with above roles can trigger the modal
      if (checkPermission(rolesWithPermission)) {
        event.preventDefault();
        ResourceModal.show('new-event-modal');
      }
    });

    HotkeysService.activateUniversalHotkey('general.new_series', function (event) {
      let rolesWithPermission = ['ROLE_UI_SERIES_CREATE', 'ROLE_ADMIN'];
      // only useres with above roles can trigger the modal
      if (checkPermission(rolesWithPermission)) {
        event.preventDefault();
        ResourceModal.show('new-series-modal');
      }
    });
  }
]);
