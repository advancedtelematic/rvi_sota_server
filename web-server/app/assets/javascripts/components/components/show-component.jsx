define(function(require) {
  var _ = require('underscore'),
      React = require('react'),
      SotaDispatcher = require('sota-dispatcher'),
      Errors = require('../errors'),
      DevicesListPanel = require('../devices/devices-list-panel'),
      db = require('stores/db');

  var ShowComponent = React.createClass({
    contextTypes: {
      router: React.PropTypes.func
    },
    componentWillUnmount: function(){
      this.props.Component.removeWatch("poll-component");
    },
    componentWillMount: function(){
      this.fetchComponent();
      this.props.Component.addWatch("poll-component", _.bind(this.forceUpdate, this, null));
    },
    fetchComponent: function() {
      var partNumber = this.context.router.getCurrentParams().partNumber;
      SotaDispatcher.dispatch({ actionType: 'get-component', partNumber: partNumber });
      SotaDispatcher.dispatch({ actionType: 'get-devices-for-component', partNumber: partNumber });
    },
    removeComponent: function() {
      SotaDispatcher.dispatch({
        actionType: 'destroy-component',
        partNumber: this.context.router.getCurrentParams().partNumber
      });
    },
    render: function() {
      var params = this.context.router.getCurrentParams();
      var listItems = _.map(this.props.Component.deref(), function(value, key) {
        return (
          <tr key={key}>
            <td>
              {key}
            </td>
            <td>
              {value}
            </td>
          </tr>
        );
      });
      return (
        <div>
          <h1>
            Components &gt; {this.props.Component.deref().partNumber}
          </h1>
          <div className="row">
            <div className="col-md-12">
              <table className="table table-striped table-bordered">
                <tbody>
                  {listItems}
                </tbody>
              </table>
            </div>
          </div>
          <button type="button" className="btn btn-primary" onClick={this.removeComponent} name="delete-component">Delete Component</button>
          <Errors />
          <br/>
          <DevicesListPanel
            Devices={db.devicesForComponent}
            PollEventName="poll-devices"
            DispatchObject={{actionType: 'get-devices-for-component', partNumber: params.partNumber}}
            Label="Devices with this component installed"/>
        </div>
      );
    }
  });

  return ShowComponent;
});
