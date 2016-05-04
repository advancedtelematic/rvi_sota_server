define(function(require) {
  var SotaDispatcher = require('sota-dispatcher'),
      _ = require('underscore'),
      db = require('../stores/db'),
      checkExists = require('../mixins/check-exists'),
      sendRequest = require('../mixins/send-request');

  function generateUUID(){
    var d = new Date().getTime();
    var uuid = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
        var r = (d + Math.random()*16)%16 | 0;
        d = Math.floor(d/16);
        return (c=='x' ? r : (r&0x3|0x8)).toString(16);
    });
    return uuid;
  }

  var createDevice = function(payload) {
    var url = '/api/v1/devices/';
    var device = {
      uuid: generateUUID(),
      deviceId: payload.device.deviceId,
      deviceType: 'Other'
    };
    // TODO: just a temporary solution; needs better abstraction
    // TODO: change route to sth sensible; /create is just a safeguard so that routes are unambiguous
    sendRequest.doPost(url + 'create', device) // send to core
      .success(function() {
        sendRequest.doPut(url + device.deviceId) // send to resolver
          .success(function () {
            SotaDispatcher.dispatch({actionType: 'search-devices-by-regex'});
          })
      })
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

            sendRequest.doGet('/api/v1/devices/search' + query)
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
