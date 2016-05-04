define(function(require) {

  var React = require('react'),
      _ = require('underscore'),
      db = require('stores/db'),
      togglePanel = require('../../mixins/toggle-panel'),
      SotaDispatcher = require('sota-dispatcher');

  var AffectedDevices = React.createClass({
    contextTypes: {
      router: React.PropTypes.func
    },
    mixins: [togglePanel],
    componentWillUnmount: function(){
      this.props.AffectedDevices.removeWatch("poll-affected-devices");
      _.each([db.packagesForFilter, db.filtersForPackage], function(atom) {
        atom.removeWatch('poll-package-filters');
      });
    },
    componentWillMount: function(){
      this.refreshData();
      this.props.AffectedDevices.addWatch("poll-affected-devices", _.bind(this.forceUpdate, this, null));
      _.each([db.packagesForFilter, db.filtersForPackage], function(atom) {
        atom.addWatch('poll-package-filters', _.bind(this.refreshData, this, null));
      }, this);
    },
    refreshData: function() {
      var params = this.context.router.getCurrentParams();
      SotaDispatcher.dispatch({
        actionType: 'fetch-affected-devices',
        name: params.name,
        version: params.version
      });
    },
    label: "Affected Devices",
    panel: function() {
      var devices = _.map(this.props.AffectedDevices.deref(), function(device) {
        return (
          <li className="list-group-item" key={device[0]}>
            { device[0] }
          </li>
        );
      });
      return (
        <div>
          <ul className={'list-group ' + (this.state.collapsed ? 'hide' : '')}>
            { devices }
          </ul>
        </div>
      );
    }
  });

  return AffectedDevices;
});
