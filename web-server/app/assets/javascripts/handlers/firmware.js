define(function(require) {
  var SotaDispatcher = require('sota-dispatcher'),
      _ = require('underscore'),
      db = require('../stores/db'),
      checkExists = require('../mixins/check-exists'),
      sendRequest = require('../mixins/send-request');

  var Handler = (function() {
      this.dispatchCallback = function(payload) {
        switch(payload.actionType) {
          case 'list-firmware-on-device':
            sendRequest.doGet('/api/v1/firmware/' + payload.device)
              .success(function(firmware) {
                db.firmwareOnDevice.reset(firmware);
              });
          break;
        }
      };
      SotaDispatcher.register(this.dispatchCallback.bind(this));
  });

  return new Handler();

});
