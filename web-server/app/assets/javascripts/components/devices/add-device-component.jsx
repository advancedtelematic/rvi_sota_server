define(function(require) {
  var React = require('react'),
      serializeForm = require('../../mixins/serialize-form'),
      toggleForm = require('../../mixins/toggle-form'),
      db = require('../../stores/db'),
      SotaDispatcher = require('sota-dispatcher');

  var AddDeviceComponent = React.createClass({
    mixins: [
      toggleForm
    ],
    handleSubmit: function(e) {
      e.preventDefault();

      payload = serializeForm(this.refs.form);
      SotaDispatcher.dispatch({
        actionType: 'create-device',
        device: payload
      });
    },
    buttonLabel: "NEW DEVICE",
    form: function() {
      return (
        <form ref='form' onSubmit={this.handleSubmit}>
          <div className="form-group">
            <label htmlFor="name">Device Name</label>
            <input type="text" className="form-control" name="deviceId" ref="deviceId" placeholder="Device UUID"/>
          </div>
          <div className="form-group">
            <button type="submit" className="btn btn-primary">Add Device</button>
          </div>
        </form>
      );
    }
  });

  return AddDeviceComponent;
});
