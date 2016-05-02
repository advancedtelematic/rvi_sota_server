define(function(require) {
  var SotaDispatcher = require('sota-dispatcher'),
      _ = require('underscore'),
      db = require('../stores/db'),
      errors = require('./errors'),
      UpdatesHandler = require('./updates'),
      filtersHandler = require('./filters'),
      devicesHandler = require('./devices'),
      packageFiltersHandler = require('./package-filters'),
      componentsHandler = require('./components'),
      firmwareHandler = require('./firmware'),
      packagesHandler = require('./packages');

  var Handler = (function() {
      this.dispatchCallback = function(payload) {
        // global logging
        console.log(payload.actionType, payload);

        // clear error messages for next request
        db.postStatus.reset("");
      };
      SotaDispatcher.register(this.dispatchCallback.bind(this));

      $(document).ajaxError(function(event, xhr) {
        if (xhr.status === 401) {
          return location.reload();
        }
        errors.renderRequestError(xhr);
      });

  });

  return new Handler();

});
