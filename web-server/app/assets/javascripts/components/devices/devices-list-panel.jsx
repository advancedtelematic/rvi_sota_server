define(function(require) {

  var React = require('react'),
      _ = require('underscore'),
      Router = require('react-router'),
      togglePanel = require('../../mixins/toggle-panel'),
      SotaDispatcher = require('sota-dispatcher');

  var DevicesListPanel = React.createClass({
    contextTypes: {
      router: React.PropTypes.func
    },
    mixins: [togglePanel],
    componentWillUnmount: function(){
      this.props.Devices.removeWatch(this.props.PollEventName);
    },
    componentWillMount: function(){
      this.refreshData();
      this.props.Devices.addWatch(this.props.PollEventName, _.bind(this.forceUpdate, this, null));
    },
    refreshData: function() {
      SotaDispatcher.dispatch(this.props.DispatchObject);
    },
    label: function() {return this.props.Label},
    panel: function() {
      var devices = _.map(this.props.Devices.deref(), function(device) {
        return (
          <li className='list-group-item' key={device}>
            <Router.Link to='device' params={{uuid: device}}>
              {device}
            </Router.Link>
          </li>
        );
      });
      return (
        <div>
          <ul className='list-group'>
            {devices}
          </ul>
        </div>
      );
    }
  });

  return DevicesListPanel;
});
