/**
 * 
 */
angular
		.module(
				'manualAuditView',
				[ 'ui.bootstrap.datetimepicker', 'ui.dateTimeInput',
						'smart-table' ])
		.factory('MonitorTypeService', [ '$resource', function($resource) {
			return $resource('/MonitorType/:id', {
				id : '@_id'
			});
		} ])
		.controller(
				'manualAuditCtrl',
				[
						'MonitorTypeService',
						'$scope',
						'$http',
						function($monitorType, $scope, $http) {
							var self = this;
							self.monitorTypeList = $monitorType.query();
							self.activeMt = function(mt) {
								return (mt.measuringBy || mt.measuredBy)
							}

							self.dateRangeStart = moment(23, "HH").subtract(2,
									'days');
							self.dateRangeEnd = moment(23, "HH");

							self.beforeRenderStartDate = function($view,
									$dates, $leftDate, $upDate, $rightDate) {
								if (self.dateRangeEnd) {
									var activeDate = moment(self.dateRangeEnd);
									for (var i = 0; i < $dates.length; i++) {
										if ($dates[i].localDateValue() >= activeDate
												.valueOf())
											$dates[i].selectable = false;
									}
								}
							}

							self.beforeRenderEndDate = function($view, $dates,
									$leftDate, $upDate, $rightDate) {
								if ($scope.dateRangeStart) {
									var activeDate = moment(self.dateRangeStart)
											.subtract(1, $view)
											.add(1, 'minute');
									for (var i = 0; i < $dates.length; i++) {
										if ($dates[i].localDateValue() <= activeDate
												.valueOf()) {
											$dates[i].selectable = false;
										}
									}
								}
							}

							$scope.selectedMtId;
							function getSelectedMt() {
								for (var i = 0; i < self.monitorTypeList.length; i++) {
									if (self.monitorTypeList[i]._id == $scope.selectedMtId)
										return self.monitorTypeList[i];
								}
							}

							self.displaySelectMtName = function() {
								var mt = getSelectedMt();
								if (mt)
									return mt.desp;
							}

							self.displayTime = function(t) {
								return moment.unix(t / 1000).format(
										"YYYY-MM-DD HH:mm");
							}

							self.getPrec = function() {
								var mt = getSelectedMt();
								if (mt)
									return mt.prec;
							}

							self.getStatus = function(value, stat) {
								var statClass = {};
								var mtCase = getSelectedMt();

								if (mtCase.std_law && value > mtCase.std_law) {
									statClass.over_law_std = true;
								} else if (mtCase.std_internal
										&& value > mtCase.std_internal) {
									statClass.over_internal_std = true;
								} else
									statClass.normal = true;

								if (stat.startsWith("0")) {
									if (stat.startsWith("01")) { // normal
									} else if (stat.startsWith("02")) { // calibration
										statClass.calibration_status = true;
									} else if (stat == "031") {
										statClass.maintain_status = true;
									} else
										statClass.abnormal_status = true;
								} else if (stat.charAt(0) == 'm'
										|| stat.charAt(0) == 'M') {
									statClass.manual_audit_status = true;
								} else
									statClass.auto_audit_status = true;
								
								return statClass;
							}

							self.showRecord = false;
							self.noData = true;
							$scope.recordList = [];

							self.query = function() {
								var url = "/Record/" + $scope.selectedMtId
										+ "/" + self.dateRangeStart.valueOf()
										+ "/" + self.dateRangeEnd.valueOf();

								$http
										.get(url)
										.then(
												function(response) {
													self.showRecord = true;
													$scope.recordList = response.data;
													self.noData = $scope.recordList.length == 0;
												}, function(error) {
												});
							}
							
							self.reason="";
							self.auditStatus="0";
							
							self.showSelected = function(){
								var desp="";
								for(var i=0;i<$scope.recordList.length;i++){
									if($scope.recordList[i].selected)
										desp += $scope.recordList[i].time+ " ";
								}
								return desp;
							}
							
							self.manualAudit = function(){
								var updateList = [];
								for(var i=0;i<$scope.recordList.length;i++){
									if($scope.recordList[i].selected){
										updateList.push({
											time: $scope.recordList[i].time,
											status: self.auditStatus + $scope.recordList[i].status.substr(1, 2)
										})
									}										
								}
								var auditParam = {
									reason:	self.reason,
									updateList: updateList
								};
								
								var url = "/Record/" + $scope.selectedMtId;
								$http.put(url, auditParam).then(function(resp){
									if(resp.data.ok){
										//reload...
										self.query();
									}									
								});
							}
						} ]);
