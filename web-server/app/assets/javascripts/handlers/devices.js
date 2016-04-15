define(function(require) {
  var SotaDispatcher = require('sota-dispatcher'),
      _ = require('underscore'),
      db = require('../stores/db'),
      checkExists = require('../mixins/check-exists'),
      sendRequest = require('../mixins/send-request');

  var createDevice = function(payload) {
    var url = '/api/v1/devices/' + payload.device.deviceId;
    sendRequest.doPut(url, payload.device)
      .success(function(devices) {
        SotaDispatcher.dispatch({actionType: 'search-devices-by-regex'});
      });
  }

  var Handler = (function() {
      this.dispatchCallback = function(payload) {
        switch(payload.actionType) {
          case 'get-devices':
            sendRequest.doGet('/api/v1/devices')
              .success(function(devices) {
                db.devices.reset(devices);
              });
          break;
          case 'create-device':
            checkExists('/api/v1/devices/' + payload.device.deviceId, "Device", function() {
              createDevice(payload);
            });
          break;
          case 'search-devices-by-regex':
            var query = payload.regex ? '?regex=' + payload.regex : '';

            sendRequest.doGet('/api/v1/devices' + query)
              .success(function(devices) {
                db.searchableDevices.reset(devices);
              });
          break;
          case 'fetch-affected-devices':
            var affectedDevicesUrl = '/api/v1/resolve/' + payload.name + "/" + payload.version;

            sendRequest.doGet(affectedDevicesUrl)
              .success(function(devices) {
                db.affectedDevices.reset(devices);
              });
          break;
          case 'get-devices-for-package':
            sendRequest.doGet('/api/v1/devices?packageName=' + payload.name + '&packageVersion=' + payload.version)
              .success(function(devices) {
                var list = _.map(devices, function(device) {
                  return device.uuid;
                });
                db.devicesForPackage.reset(list);
              });
          break;
          case 'get-package-queue-for-device':
            sendRequest.doGet('/api/v1/devices/' + payload.device + '/queued')
              .success(function(packages) {
                db.packageQueueForDevice.reset(packages);
              });
          break;
          case 'get-package-history-for-device':
            sendRequest.doGet('/api/v1/devices/' + payload.device + '/history')
              .success(function(packages) {
                db.packageHistoryForDevice.reset(packages);
              });
          break;
          case 'list-components-on-device':
            sendRequest.doGet('/api/v1/devices/' + payload.device + '/component')
              .success(function(components) {
                db.componentsOnDevice.reset(components);
              });
          break;
          case 'add-component-to-device':
            sendRequest.doPut('/api/v1/devices/' + payload.device + '/component/' + payload.partNumber)
              .success(function() {
                SotaDispatcher.dispatch({actionType: 'list-components-on-device', device: payload.device});
              });
          break;
          case 'sync-packages-for-device':
            sendRequest.doPut('/api/v1/devices/' + payload.device + '/sync');
          break;
        }
      };
      SotaDispatcher.register(this.dispatchCallback.bind(this));
  });

  return new Handler();

});
